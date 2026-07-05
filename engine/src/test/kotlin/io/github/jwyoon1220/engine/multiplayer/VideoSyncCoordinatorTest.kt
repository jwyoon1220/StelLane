package io.github.jwyoon1220.engine.multiplayer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * [VideoSyncCoordinator]의 NTP 오프셋/RTT 계산과 스케줄링 지연 계산에 대한 순수 함수 테스트.
 * 네트워크나 ffmpeg 없이 결정적으로 실행됩니다.
 */
class VideoSyncCoordinatorTest {

    @Test
    fun `왕복 지연이 없으면 오프셋과 RTT가 0이다`() {
        // t0=100(클라이언트 송신), t1=100(호스트 수신), t2=100(호스트 송신), t3=100(클라이언트 수신)
        val sample = VideoSyncCoordinator.computeSample(t0 = 100L, t1 = 100L, t2 = 100L, t3 = 100L)
        assertEquals(0L, sample.offsetMs)
        assertEquals(0L, sample.rttMs)
    }

    @Test
    fun `호스트 클럭이 앞서 있으면 양의 오프셋을 계산한다`() {
        // 클라이언트가 t0=1000에 보내고 t3=1040에 받음(왕복 40ms, 편도 20ms 가정).
        // 호스트 클럭이 클라이언트보다 500ms 앞서 있다면 t1=1520, t2=1520.
        val sample = VideoSyncCoordinator.computeSample(t0 = 1000L, t1 = 1520L, t2 = 1520L, t3 = 1040L)
        assertEquals(500L, sample.offsetMs)
        assertEquals(40L, sample.rttMs)
    }

    @Test
    fun `호스트 클럭이 뒤처져 있으면 음의 오프셋을 계산한다`() {
        val sample = VideoSyncCoordinator.computeSample(t0 = 1000L, t1 = 700L, t2 = 700L, t3 = 1040L)
        assertEquals(-320L, sample.offsetMs)
        assertEquals(40L, sample.rttMs)
    }

    @Test
    fun `중앙값은 홀수 개 표본에서 가운데 값을 반환한다`() {
        assertEquals(20L, VideoSyncCoordinator.median(listOf(10L, 20L, 30L)))
        assertEquals(20L, VideoSyncCoordinator.median(listOf(30L, 10L, 20L)))
    }

    @Test
    fun `중앙값은 짝수 개 표본에서 가운데 두 값의 평균을 반환한다`() {
        assertEquals(15L, VideoSyncCoordinator.median(listOf(10L, 20L)))
        assertEquals(25L, VideoSyncCoordinator.median(listOf(10L, 20L, 30L, 40L)))
    }

    @Test
    fun `표본이 없으면 중앙값은 0이다`() {
        assertEquals(0L, VideoSyncCoordinator.median(emptyList()))
    }

    @Test
    fun `computeScheduleDelayMs는 오프셋과 실측 지연을 반영한다`() {
        val manager = MultiplayerManager()
        val coordinator = VideoSyncCoordinator(manager)
        coordinator.applyKnownOffset(0L) // 로컬-호스트 클럭이 동일하다고 가정

        // 호스트가 5000ms 뒤(현재+5000)에 시작하도록 지시했고, 실측 시작 지연이 200ms라면
        // 로컬은 (5000 - 0 - 200) = 4800ms 후에 play()를 호출해야 함.
        val hostEpoch = System.currentTimeMillis() + 5000L
        val delay = coordinator.computeScheduleDelayMs(hostEpoch, measuredStartupLatencyMs = 200L)

        // 테스트 실행 중 약간의 시간 경과를 감안해 근사 비교
        assertTrue(abs(delay - 4800L) <= 200L, "expected ~4800ms, was ${delay}ms")
    }

    @Test
    fun `이미 지난 시각이면 지연은 0으로 클램프된다`() {
        val manager = MultiplayerManager()
        val coordinator = VideoSyncCoordinator(manager)
        coordinator.applyKnownOffset(0L)

        val pastEpoch = System.currentTimeMillis() - 10_000L
        val delay = coordinator.computeScheduleDelayMs(pastEpoch, measuredStartupLatencyMs = 0L)

        assertEquals(0L, delay)
    }
}
