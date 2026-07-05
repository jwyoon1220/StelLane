package io.github.jwyoon1220.engine.multiplayer

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [HlsServer]의 ffmpeg 세그먼트화 통합 테스트.
 *
 * 실제 매치 영상은 더 이상 스트리밍하지 않고(전체 파일 전송) 오프셋 보정용 테스트 클립만
 * HLS로 세그먼트화하므로, [HlsServer.ensureTestStreamSegmented]만 검증합니다.
 *
 * 테스트 전제 조건:
 *  - ffmpeg 바이너리가 번들(ffmpeg/ffmpeg.exe) 또는 시스템 PATH에 있어야 합니다.
 *  - "run/songs/STAR_TRAIL/video.mp4" 파일이 존재해야 합니다(테스트 클립 대용으로 재사용).
 *
 * 둘 중 하나라도 없으면 테스트는 건너뜁니다(Assumption fail).
 */
class HlsServerTest {

    private val cacheDir = File("build/test-hls-cache/${System.nanoTime()}")

    companion object {
        private val TEST_CLIP: File by lazy {
            val candidates = listOf(
                File("run/songs/STAR_TRAIL/video.mp4"),
                File("../run/songs/STAR_TRAIL/video.mp4")
            )
            candidates.firstOrNull { it.exists() } ?: candidates[0]
        }
    }

    @AfterEach
    fun tearDown() {
        runCatching { cacheDir.deleteRecursively() }
    }

    @Test
    fun `테스트 클립을 HLS로 세그먼트화하면 매니페스트와 세그먼트가 생성된다`() {
        val ffmpegPath = HlsServer.resolveFfmpegPath()
        assumeTrue(ffmpegPath != null, "ffmpeg를 찾을 수 없어 테스트를 건너뜁니다")
        assumeTrue(TEST_CLIP.exists(), "테스트 클립 없음: ${TEST_CLIP.absolutePath}")

        val server = HlsServer(ffmpegPath)
        val manifest = server.ensureTestStreamSegmented(TEST_CLIP, cacheDir)

        assertTrue(manifest != null, "세그먼트화 실패 — 매니페스트가 생성되지 않음")
        assertTrue(manifest!!.exists(), "매니페스트 파일이 생성되지 않음")

        val segmentCount = manifest.readLines().count { it.startsWith("#EXTINF") }
        assertTrue(segmentCount > 0, "세그먼트가 하나도 생성되지 않음")

        val initFile = File(cacheDir, "init.mp4")
        assertTrue(initFile.exists() && initFile.length() > 0, "fMP4 init 세그먼트가 생성되지 않음")

        val segFiles = cacheDir.listFiles { f -> f.extension == "m4s" } ?: emptyArray()
        assertTrue(segFiles.isNotEmpty(), ".m4s 세그먼트 파일이 생성되지 않음")
        assertTrue(segFiles.all { it.length() > 0 }, "빈 .m4s 세그먼트 파일이 존재함")
    }

    @Test
    fun `ffmpeg가 없으면 null을 반환한다`() {
        val server = HlsServer(ffmpegPath = null)
        val manifest = server.ensureTestStreamSegmented(TEST_CLIP, cacheDir)
        assertTrue(manifest == null, "ffmpeg 없이도 매니페스트가 생성됨")
    }
}
