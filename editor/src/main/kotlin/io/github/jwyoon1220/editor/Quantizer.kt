package io.github.jwyoon1220.editor

import kotlin.math.roundToLong

object Quantizer {
    /**
     * [timeMs]를 BPM과 비트 분할([division]: 4=1/4, 8=1/8, 16=1/16)에 맞춰 스냅합니다.
     * bpm ≤ 0 이거나 division ≤ 0 이면 원본을 그대로 반환합니다.
     */
    fun snap(timeMs: Long, bpm: Int, division: Int): Long {
        if (bpm <= 0 || division <= 0) return timeMs
        val subdivisionMs = 60_000.0 / bpm / division
        return (timeMs / subdivisionMs).roundToLong() * subdivisionMs.toLong()
    }
}
