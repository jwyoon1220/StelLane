package io.github.jwyoon1220.engine

import io.github.jwyoon1220.engine.multiplayer.HlsServer
import io.ktor.http.ContentType
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * VideoBackground VLC 재생 통합 테스트.
 *
 * 테스트 전제 조건:
 *  - 프로젝트 루트 기준 "vlc/" 또는 시스템에 VLC 3.x가 설치되어 있어야 합니다.
 *  - "run/songs/STAR_TRAIL/video.mp4" 파일이 존재해야 합니다.
 *
 * VLC 또는 비디오 파일이 없으면 테스트는 건너뜁니다(Assumption fail).
 *
 * [VLC 3.x + AV1(dav1d) 알려진 동작]
 * BT.709 컬러스페이스 AV1 소스에서 VLC 내부적으로 색공간 변환 필터 체인을
 * 재귀적으로 시도합니다. --quiet 옵션으로 이 노이즈를 억제하고,
 * I420 포맷 + fallback 경로로 정상 재생합니다.
 */
class VideoBackgroundTest {

    private lateinit var vb: VideoBackground

    companion object {
        /** 테스트용 비디오 파일. engine/ 또는 프로젝트 루트 기준 상대 경로를 시도. */
        private val VIDEO_FILE: File by lazy {
            val candidates = listOf(
                File("run/songs/STAR_TRAIL/video.mp4"),      // 워킹 디렉토리 = 프로젝트 루트
                File("../run/songs/STAR_TRAIL/video.mp4")   // 워킹 디렉토리 = engine/
            )
            candidates.firstOrNull { it.exists() }
                ?: candidates[0]
        }
    }

    @BeforeEach
    fun setUp() {
        vb = VideoBackground.create()
    }

    @AfterEach
    fun tearDown() {
        runCatching { vb.stop() }
        Thread.sleep(200)
        runCatching { vb.release() }
        Thread.sleep(200)
    }

    /**
     * VLC 파이프라인 동작 테스트: AV1 비디오 재생이 시작되고 프레임이 수신됨을 확인.
     *
     * 수정 전 동작: RV32 포맷 요청 시 VLC 필터 체인 재귀로 video output 생성 완전 실패.
     * 수정 후 동작: I420 포맷 + --quiet 으로 VLC 내부 재귀를 거친 뒤 fallback으로 프레임 전달.
     */
    @Test
    fun `AV1 비디오 파이프라인이 사용 가능하고 프레임이 수신된다`() {
        assumeTrue(vb.isAvailable, "VLC를 사용할 수 없어 테스트를 건너뜁니다")
        assumeTrue(VIDEO_FILE.exists(), "테스트 비디오 없음: ${VIDEO_FILE.absolutePath}")

        val playingLatch = CountDownLatch(1)
        vb.onPlayingStarted = { playingLatch.countDown() }

        vb.play(VIDEO_FILE.absolutePath)
        assertTrue(playingLatch.await(8, TimeUnit.SECONDS), "8초 내에 VLC 재생이 시작되지 않았습니다")

        val deadline = System.currentTimeMillis() + 3_000
        while (vb.getCurrentFrame() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertNotNull(vb.getCurrentFrame(), "VLC로부터 비디오 프레임을 받지 못했습니다")
    }

    /**
     * 재생 시작 후 수신된 프레임의 크기가 유효한지 확인합니다.
     */
    @Test
    fun `재생 시작 후 프레임이 수신된다`() {
        assumeTrue(vb.isAvailable, "VLC를 사용할 수 없어 테스트를 건너뜁니다")
        assumeTrue(VIDEO_FILE.exists(), "테스트 비디오 없음: ${VIDEO_FILE.absolutePath}")

        val playingLatch = CountDownLatch(1)
        vb.onPlayingStarted = { playingLatch.countDown() }

        vb.play(VIDEO_FILE.absolutePath)
        val started = playingLatch.await(8, TimeUnit.SECONDS)
        assertTrue(started, "8초 내에 VLC 재생이 시작되지 않았습니다")

        val deadline = System.currentTimeMillis() + 3_000
        while (vb.getCurrentFrame() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        val frame = vb.getCurrentFrame()
        assertNotNull(frame, "VLC로부터 비디오 프레임을 받지 못했습니다")
        assertTrue((frame!!.width > 0) && (frame.height > 0),
            "수신된 프레임 크기가 유효하지 않습니다: ${frame.width}x${frame.height}")
    }

    /**
     * getSmoothTimeMs()가 재생 시작 후 (역행 없이) 결국 진행되는 값을 반환하는지 확인합니다.
     *
     * 소프트웨어 AV1 디코드는 시스템 부하에 따라 짧게(수백 ms) 실시간을 따라잡지 못하고 멈출 수
     * 있으므로 — 이 경우 getSmoothTimeMs는 (역행하지 않고) 값을 유지한 채 대기하는 것이 올바른
     * 동작이다 — 고정된 짧은 구간에서 "반드시 증가"를 요구하지 않고, 넉넉한 타임아웃 내에서
     * "역행 없이 결국 진행되는지"를 폴링으로 확인한다.
     */
    @Test
    fun `재생 중 getSmoothTimeMs가 진행된다`() {
        assumeTrue(vb.isAvailable, "VLC를 사용할 수 없어 테스트를 건너뜁니다")
        assumeTrue(VIDEO_FILE.exists(), "테스트 비디오 없음: ${VIDEO_FILE.absolutePath}")

        val playingLatch = CountDownLatch(1)
        vb.onPlayingStarted = { playingLatch.countDown() }

        vb.play(VIDEO_FILE.absolutePath)
        assertTrue(playingLatch.await(8, TimeUnit.SECONDS), "재생 시작 타임아웃")

        Thread.sleep(200)
        val t1 = vb.getSmoothTimeMs()

        val deadline = System.currentTimeMillis() + 8_000
        var lastSeen = t1
        var progressed = false
        while (System.currentTimeMillis() < deadline) {
            val t = vb.getSmoothTimeMs()
            assertTrue(t >= lastSeen, "getSmoothTimeMs가 역행함: 이전=$lastSeen 현재=$t")
            lastSeen = t
            if (t > t1) { progressed = true; break }
            Thread.sleep(50)
        }
        assertTrue(progressed, "8초 내에 getSmoothTimeMs가 진행되지 않음: t1=$t1")
    }

    /**
     * libVLC가 로컬 파일 경로뿐 아니라 HTTP로 서빙되는 HLS 스트림도 동일하게 재생할 수 있는지
     * 확인합니다 — 멀티플레이어 영상 스트리밍 기능의 핵심 가정을 문서가 아닌 실제 테스트로 검증.
     */
    @Test
    fun `HTTP로 서빙되는 HLS 스트림도 재생된다`() {
        assumeTrue(vb.isAvailable, "VLC를 사용할 수 없어 테스트를 건너뜁니다")
        assumeTrue(VIDEO_FILE.exists(), "테스트 비디오 없음: ${VIDEO_FILE.absolutePath}")
        val ffmpegPath = HlsServer.resolveFfmpegPath()
        assumeTrue(ffmpegPath != null, "ffmpeg를 찾을 수 없어 테스트를 건너뜁니다")

        val workDir = File("build/test-hls-http/${System.nanoTime()}")
        val hlsServer = HlsServer(ffmpegPath)
        val manifest = hlsServer.ensureTestStreamSegmented(VIDEO_FILE, workDir)
        assertTrue(manifest != null && manifest.exists(), "매니페스트 생성 실패 — HTTP 서빙 테스트 불가")

        var server: EmbeddedServer<*, *>? = null
        val port = 18173
        try {
            server = embeddedServer(CIO, port = port) {
                install(PartialContent)
                install(AutoHeadResponse)
                routing {
                    get("/{fileName}") {
                        val name = call.parameters["fileName"]!!
                        val file = File(workDir, name)
                        val contentType = when {
                            name.endsWith(".m3u8") -> ContentType.parse("application/vnd.apple.mpegurl")
                            name.endsWith(".m4s")  -> ContentType.parse("video/iso.segment")
                            name.endsWith(".mp4")  -> ContentType.parse("video/mp4")
                            else -> ContentType.parse("video/mp2t")
                        }
                        call.respond(LocalFileContent(file, contentType))
                    }
                }
            }.start(wait = false)

            val playingLatch = CountDownLatch(1)
            vb.onPlayingStarted = { playingLatch.countDown() }
            vb.play("http://127.0.0.1:$port/master.m3u8")

            assertTrue(playingLatch.await(15, TimeUnit.SECONDS), "15초 내에 HTTP HLS 재생이 시작되지 않았습니다")

            // HTTP로 서빙되는 HLS는 세그먼트 페치/버퍼링 때문에 로컬 파일보다 첫 프레임까지 더 걸림
            val deadline = System.currentTimeMillis() + 10_000
            while (vb.getCurrentFrame() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertNotNull(vb.getCurrentFrame(), "HTTP HLS 스트림으로부터 프레임을 받지 못했습니다")
        } finally {
            server?.stop(500, 500)
            workDir.deleteRecursively()
        }
    }
}
