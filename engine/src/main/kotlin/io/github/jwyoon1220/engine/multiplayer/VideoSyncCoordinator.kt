package io.github.jwyoon1220.engine.multiplayer

import io.github.jwyoon1220.engine.VideoBackground
import io.github.jwyoon1220.engine.multiplayer.proto.ClockPongMsg
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NTP 스타일 클럭 오프셋 계산 + 스트림 실측 지연 측정 헬퍼.
 *
 * Scene/GameContext에 의존하지 않아 호스트(로컬 프로브, 오프셋 0 고정)와 클라이언트(네트워크 핑퐁)
 * 양쪽에서 공용으로 사용합니다.
 */
class VideoSyncCoordinator(private val manager: MultiplayerManager) {

    private val log = LoggerFactory.getLogger(VideoSyncCoordinator::class.java)

    data class PingSample(val offsetMs: Long, val rttMs: Long)

    /** 로컬 시각에 더하면 호스트 시각이 되는 보정값. */
    @Volatile var clockOffsetMs: Long = 0L
        private set
    @Volatile var lastRttMs: Long = 0L
        private set

    private val samples = CopyOnWriteArrayList<PingSample>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "video-sync-ping").apply { isDaemon = true }
    }
    private val roundDone = AtomicBoolean(false)

    /** 호스트 자신처럼 네트워크 왕복이 필요 없는 경우, 오프셋을 알려진 값(보통 0)으로 고정합니다. */
    fun applyKnownOffset(offsetMs: Long) {
        clockOffsetMs = offsetMs
    }

    /**
     * [sampleCount]회 ping-pong 왕복을 수행해 [clockOffsetMs]/[lastRttMs]를 중앙값으로 계산합니다.
     * [ROUND_TIMEOUT_MS] 내 충분한 샘플을 못 받아도 수집된 것만으로 계산을 완료합니다
     * (표본이 0개면 오프셋은 0으로 유지).
     */
    fun beginPingRound(sampleCount: Int = 5, onDone: () -> Unit) {
        roundDone.set(false)
        samples.clear()
        manager.onClockPong = { pong -> onPong(pong) }

        var sent = 0
        val sendTask = scheduler.scheduleAtFixedRate({
            if (sent < sampleCount) {
                manager.sendClockPing(System.currentTimeMillis())
                sent++
            }
        }, 0, PING_INTERVAL_MS, TimeUnit.MILLISECONDS)

        scheduler.schedule({
            sendTask.cancel(false)
            finishRound(onDone)
        }, ROUND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun onPong(pong: ClockPongMsg) {
        val t3 = System.currentTimeMillis()
        samples.add(computeSample(pong.t0, pong.t1, pong.t2, t3))
    }

    private fun finishRound(onDone: () -> Unit) {
        if (!roundDone.compareAndSet(false, true)) return
        manager.onClockPong = null
        if (samples.isNotEmpty()) {
            clockOffsetMs = median(samples.map { it.offsetMs })
            lastRttMs = median(samples.map { it.rttMs })
        }
        log.debug("[VideoSyncCoordinator] 클럭 보정 완료: offset={}ms rtt={}ms (samples={})",
            clockOffsetMs, lastRttMs, samples.size)
        onDone()
    }

    /**
     * [url]을 [videoBackground]로 재생 시작해 실제로 재생이 시작될 때까지(onPlayingStarted)의
     * wall-clock 경과 시간을 측정합니다 — HLS 매니페스트/세그먼트 페치 + VLC 디먹스/디코드 시작
     * 지연을 전부 포함하는 종단간 측정입니다.
     */
    fun measureStreamStartupLatency(videoBackground: VideoBackground, url: String, onResult: (latencyMs: Long) -> Unit) {
        val t0 = System.currentTimeMillis()
        videoBackground.onPlayingStarted = {
            onResult(System.currentTimeMillis() - t0)
        }
        videoBackground.play(url)
    }

    /** [syncEpochMsHost](호스트 wall-clock)에 로컬에서 play()가 호출되어야 할 지연(ms)을 계산합니다. */
    fun computeScheduleDelayMs(syncEpochMsHost: Long, measuredStartupLatencyMs: Long): Long {
        val localEpochEquivalent = syncEpochMsHost - clockOffsetMs
        val playAtLocal = localEpochEquivalent - measuredStartupLatencyMs
        return (playAtLocal - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun shutdown() {
        scheduler.shutdownNow()
    }

    companion object {
        private const val PING_INTERVAL_MS = 150L
        private const val ROUND_TIMEOUT_MS = 2000L

        /** 표준 NTP 왕복 공식으로 단일 샘플의 클럭 오프셋/RTT를 계산합니다. 순수 함수(테스트용으로 공개). */
        internal fun computeSample(t0: Long, t1: Long, t2: Long, t3: Long): PingSample {
            val offset = ((t1 - t0) + (t2 - t3)) / 2
            val rtt = (t3 - t0) - (t2 - t1)
            return PingSample(offset, rtt)
        }

        /** 표본들의 중앙값. 짝수 개면 가운데 두 값의 평균. 순수 함수(테스트용으로 공개). */
        internal fun median(values: List<Long>): Long {
            if (values.isEmpty()) return 0L
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
        }
    }
}
