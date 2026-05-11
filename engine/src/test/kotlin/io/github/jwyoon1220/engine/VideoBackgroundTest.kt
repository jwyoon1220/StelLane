package io.github.jwyoon1220.engine

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
     * getSmoothTimeMs()가 재생 시작 후 증가하는 값을 반환하는지 확인합니다.
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
        Thread.sleep(500)
        val t2 = vb.getSmoothTimeMs()

        assertTrue(t2 > t1, "getSmoothTimeMs 가 증가하지 않음: t1=$t1 t2=$t2")
    }
}
