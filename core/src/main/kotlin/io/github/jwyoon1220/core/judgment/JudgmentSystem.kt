package io.github.jwyoon1220.core.judgment

import kotlin.math.abs

object JudgmentSystem {
    const val PERFECT_MS = 50L
    const val GREAT_MS   = 100L
    const val GOOD_MS    = 150L

    fun judge(hitTimeMs: Long, noteTimeMs: Long): Judgment {
        val diff = abs(hitTimeMs - noteTimeMs)
        return when {
            diff <= PERFECT_MS -> Judgment.PERFECT
            diff <= GREAT_MS   -> Judgment.GREAT
            diff <= GOOD_MS    -> Judgment.GOOD
            else               -> Judgment.MISS
        }
    }
}
