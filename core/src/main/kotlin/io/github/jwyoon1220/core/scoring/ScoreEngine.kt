package io.github.jwyoon1220.core.scoring

import io.github.jwyoon1220.core.judgment.Judgment

/**
 * 최고점 1,000,000점 정규화 스코어링.
 *
 * 기본점수 900,000점: 노트별 판정 가중치(PERFECT=1.0 / GREAT=0.7 / GOOD=0.3 / MISS=0) 합산 정규화.
 * 콤보 보너스  100,000점: 누적 콤보의 삼각수 비율로 비례 배분.
 */
class ScoreEngine(private val totalNotes: Int) {

    private val BASE_SCORE        = 900_000.0
    private val COMBO_BONUS_MAX   = 100_000.0

    @Volatile var score: Int = 0
        private set

    @Volatile var maxCombo: Int = 0
        private set

    private var weightedSum  = 0.0
    private var comboSum     = 0L
    private var currentCombo = 0

    // 판정별 카운트 (렌더링에서 표시용)
    val counts = mutableMapOf(
        Judgment.PERFECT to 0,
        Judgment.GREAT   to 0,
        Judgment.GOOD    to 0,
        Judgment.MISS    to 0
    )

    private val noteWeight = if (totalNotes > 0) BASE_SCORE / totalNotes else 0.0
    // 최대 가능 콤보 누적 합 = n*(n+1)/2
    private val maxComboSum = totalNotes.toLong() * (totalNotes + 1) / 2

    fun onJudgment(judgment: Judgment) {
        counts[judgment] = (counts[judgment] ?: 0) + 1

        val factor = when (judgment) {
            Judgment.PERFECT -> 1.0
            Judgment.GREAT   -> 0.7
            Judgment.GOOD    -> 0.3
            Judgment.MISS    -> 0.0
        }
        weightedSum += noteWeight * factor

        if (judgment == Judgment.MISS) {
            currentCombo = 0
        } else {
            currentCombo++
            if (currentCombo > maxCombo) maxCombo = currentCombo
            comboSum += currentCombo
        }

        val comboBonus = if (maxComboSum > 0)
            comboSum.toDouble() / maxComboSum * COMBO_BONUS_MAX
        else 0.0

        score = (weightedSum + comboBonus).toInt().coerceIn(0, 1_000_000)
    }

    fun reset() {
        score = 0; maxCombo = 0; weightedSum = 0.0
        comboSum = 0; currentCombo = 0
        counts.keys.forEach { counts[it] = 0 }
    }
}
