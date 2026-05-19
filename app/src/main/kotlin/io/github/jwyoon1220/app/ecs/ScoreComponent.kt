package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.engine.ecs.Component

/**
 * 현재 게임 점수 및 콤보 상태.
 *
 * JudgmentResultComponent 이벤트 처리 시 업데이트됨.
 * 렌더링과 UI 표시에 사용됨.
 */
data class ScoreComponent(
    /** 누적 점수 */
    val score: Int = 0,

    /** 현재 콤보 (MISS 시 0으로 리셋) */
    val combo: Int = 0,

    /** 최대 콤보 (누적) */
    val maxCombo: Int = 0,

    /** 판정 카운트: [PERFECT, GREAT, GOOD, MISS] */
    val judgmentCounts: IntArray = intArrayOf(0, 0, 0, 0)
) : Component
