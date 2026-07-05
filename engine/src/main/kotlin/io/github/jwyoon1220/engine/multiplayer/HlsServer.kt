package io.github.jwyoon1220.engine.multiplayer

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * ffmpeg를 이용해 오프셋 보정용 테스트 클립을 HLS(.m3u8 + fMP4 `.m4s` 세그먼트)로 세그먼트화합니다.
 *
 * 실제 매치 영상은 스트리밍하지 않고 차트/오디오와 동일하게 전체 파일로 전송합니다 — 이 클래스는
 * 오프셋 보정 단계에서 재생하는 짧은 테스트 클립에만 쓰입니다.
 *
 * 컨테이너로 fMP4(fragmented MP4)를 사용합니다 — 전통적인 MPEG-TS는 AV1 등 일부 코덱의 영상
 * 트랙을 표현할 표준 매핑이 없어 `-c copy` 시 영상이 조용히 누락되지만, fMP4는 MP4가 지원하는
 * 사실상 모든 코덱(H.264/HEVC/AV1/VP9/MPEG-4 등)을 그대로(무손실 리먹스로) 담을 수 있습니다.
 * transcode는 그래도 실패하는 극히 예외적인 코덱에 대한 최후의 안전망으로만 유지합니다.
 *
 * 네트워킹/세션 상태는 다루지 않고 순수하게 ffmpeg 프로세스 실행과 산출물 생명주기만 책임집니다.
 * HTTP 서빙은 [MultiplayerManager]가 담당합니다.
 */
class HlsServer(private val ffmpegPath: String?) {

    private val log = LoggerFactory.getLogger(HlsServer::class.java)

    /**
     * 오프셋 보정용 테스트 클립을 [cacheDir]에 세그먼트화합니다(최초 1회, 이미 세그먼트화되어 있으면
     * 재사용). 클립이 짧으므로(수 초) 동기적으로 완료를 기다립니다.
     */
    fun ensureTestStreamSegmented(testClip: File, cacheDir: File): File? {
        val ffmpeg = ffmpegPath ?: return null
        val manifest = File(cacheDir, "master.m3u8")
        if (manifest.exists() && readySegmentCount(manifest) > 0) return manifest

        return try {
            Files.createDirectories(cacheDir.toPath())
            val copyOk = runToCompletion(ffmpeg, testClip, manifest, useCopy = true, timeoutSec = 20) &&
                probeHasVideoStream(ffmpeg, cacheDir)
            if (!copyOk) {
                log.info("[HlsServer] 테스트 클립 -c copy 실패(또는 영상 스트림 누락), transcode 재시도: {}", testClip.name)
                runToCompletion(ffmpeg, testClip, manifest, useCopy = false, timeoutSec = 30)
            }
            if (readySegmentCount(manifest) > 0) manifest else null
        } catch (e: Exception) {
            log.warn("[HlsServer] 테스트 스트림 세그먼트화 실패: {}", e.message)
            null
        }
    }

    // ── 내부 구현 ────────────────────────────────────────────────────────────

    /** 동기적으로 ffmpeg를 실행해 완료까지 대기합니다. 성공 시 true. */
    private fun runToCompletion(ffmpeg: String, input: File, manifest: File, useCopy: Boolean, timeoutSec: Long): Boolean {
        val process = spawnFfmpeg(ffmpeg, input, manifest, useCopy)
        val exited = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }

    private fun spawnFfmpeg(ffmpeg: String, input: File, manifest: File, useCopy: Boolean): Process {
        val args = buildArgs(ffmpeg, input, manifest, useCopy)
        // CWD를 출력 디렉터리로 고정해 -hls_fmp4_init_filename에 순수 파일명만 넘길 수 있게 한다.
        // (절대 경로를 넘기면 그 문자열이 그대로 매니페스트의 #EXT-X-MAP URI에 박혀버려, HTTP로
        // 서빙할 때 완전히 깨진 URL이 되어 클라이언트가 init 세그먼트를 가져오지 못한다 —
        // -hls_segment_filename과 달리 fmp4 init 파일명은 자동으로 basename만 추출되지 않음)
        val process = ProcessBuilder(args).directory(manifest.parentFile).redirectErrorStream(true).start()
        drainProcessOutput(process)
        return process
    }

    private fun buildArgs(ffmpeg: String, input: File, manifest: File, useCopy: Boolean): List<String> {
        // transcode 폴백(fMP4로도 담을 수 없는 극히 예외적인 코덱)만 화질보다 준비 속도를
        // 우선해 720p로 축소 + ultrafast 프리셋을 사용한다.
        val codecArgs = if (useCopy) listOf("-c", "copy") else
            listOf("-vf", "scale=-2:720", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac")
        // 세그먼트/매니페스트도 순수 파일명으로(CWD=출력 디렉터리 기준) 일관되게 지정 —
        // init 세그먼트와 동일한 방식이라야 매니페스트에 어떤 방식으로 경로가 박히든 안전하다.
        return listOf(ffmpeg, "-y", "-i", input.absolutePath) + codecArgs + listOf(
            "-start_number", "0",
            "-hls_time", SEGMENT_DURATION_SEC.toString(),
            "-hls_list_size", "0",
            "-hls_segment_type", "fmp4",
            "-hls_fmp4_init_filename", INIT_SEGMENT_NAME,
            "-hls_flags", "independent_segments",
            "-hls_segment_filename", "seg_%05d.m4s",
            manifest.name
        )
    }

    /** ffmpeg stdout/stderr(병합됨)를 데몬 스레드에서 드레인해 파이프 버퍼 데드락을 방지합니다. */
    private fun drainProcessOutput(process: Process) {
        Thread({
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line -> log.trace("[ffmpeg] {}", line) }
            }
        }, "hls-ffmpeg-drain").apply { isDaemon = true; start() }
    }

    /** 매니페스트 파일의 EXTINF(세그먼트) 라인 수를 셉니다. 파일이 없으면 0. */
    private fun readySegmentCount(manifest: File): Int {
        if (!manifest.exists()) return 0
        return runCatching {
            manifest.readLines().count { it.startsWith("#EXTINF") }
        }.getOrDefault(0)
    }

    /**
     * ffmpeg 자신으로 짧게(0.1초) 재probe해 실제 영상 스트림이 존재하는지 확인합니다. -c copy는
     * 원본 코덱을 fMP4로도 담을 수 없는 극히 드문 경우 exit code 0으로 "성공"하면서도 영상
     * 트랙을 조용히 누락시킬 수 있어, exit code만으로는 이 실패를 감지할 수 없습니다.
     *
     * fMP4의 개별 `.m4s` 조각은 init 세그먼트(moov 박스)의 샘플 설명 없이는 스스로를 설명하지
     * 못하므로, init 세그먼트 + 첫 조각을 이어붙인 임시 파일을 probe합니다. 매니페스트(.m3u8)
     * 자체는 probe하지 않습니다 — #EXT-X-ENDLIST가 아직 없는(= ffmpeg가 계속 세그먼트를 쓰는
     * 중인) 매니페스트를 ffmpeg의 `-i`로 열면 "라이브" 플레이리스트로 간주해 더 많은 세그먼트를
     * 무한정 기다리며 멈출 수 있음.
     */
    private fun probeHasVideoStream(ffmpeg: String, outputDir: File): Boolean {
        val initFile = File(outputDir, INIT_SEGMENT_NAME)
        // init 세그먼트는 manifest.tmp와 비슷하게 임시로 쓰였다가 rename될 수 있어, EXTINF가
        // manifest에 이미 나타난 시점에도 아주 짧게 아직 보이지 않을 수 있다 — 최대 1초 대기.
        val initDeadline = System.currentTimeMillis() + 1_000L
        while (!initFile.exists() && System.currentTimeMillis() < initDeadline) {
            Thread.sleep(50)
        }
        val segmentFile = outputDir.listFiles { f -> f.extension == "m4s" }?.minByOrNull { it.name }
        if (!initFile.exists() || segmentFile == null) {
            log.debug("[HlsServer] probeHasVideoStream: init/segment 파일 없음 (init={}, segment={})",
                initFile.exists(), segmentFile?.name)
            return false
        }

        return runCatching {
            val combined = File.createTempFile("hls-probe-", ".mp4", outputDir)
            try {
                combined.outputStream().use { out ->
                    initFile.inputStream().use { it.copyTo(out) }
                    segmentFile.inputStream().use { it.copyTo(out) }
                }
                val process = ProcessBuilder(ffmpeg, "-i", combined.absolutePath, "-t", "0.1", "-f", "null", "-")
                    .redirectErrorStream(true).start()
                // readText()가 절대 블로킹되지 않도록 워치독으로 강제 종료 보장
                Thread({ if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly() },
                    "hls-probe-watchdog").apply { isDaemon = true; start() }
                val output = process.inputStream.bufferedReader().readText()
                val inputSection = output.substringAfter("Input #0", "").substringBefore("Output #0", output)
                val hasVideo = inputSection.contains(": Video:")
                log.debug("[HlsServer] probeHasVideoStream: init={}({}B) seg={}({}B) hasVideo={}",
                    initFile.name, initFile.length(), segmentFile.name, segmentFile.length(), hasVideo)
                hasVideo
            } finally {
                combined.delete()
            }
        }.onFailure { e -> log.debug("[HlsServer] probeHasVideoStream 예외 발생: {}", e.toString()) }
            .getOrDefault(false)
    }

    companion object {
        const val SEGMENT_DURATION_SEC = 2
        private const val INIT_SEGMENT_NAME = "init.mp4"

        /** 번들 경로(작업 디렉터리 기준 여러 후보) 우선 탐색, 없으면 시스템 PATH에서 ffmpeg 탐색. */
        fun resolveFfmpegPath(): String? {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val exeName = if (isWindows) "ffmpeg.exe" else "ffmpeg"
            val candidates = listOf(
                File("ffmpeg/$exeName"),
                File("../ffmpeg/$exeName"),
                File(System.getProperty("user.dir"), "ffmpeg/$exeName")
            )
            candidates.firstOrNull { it.exists() }?.let { return it.absolutePath }
            return runCatching {
                val lookupCmd = if (isWindows) "where" else "which"
                ProcessBuilder(lookupCmd, "ffmpeg").start()
                    .inputStream.bufferedReader().readLine()?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }
}
