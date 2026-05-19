package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.engine.ecs.Component
import io.github.jwyoon1220.core.judgment.Judgment

/**
 * 판정 결과 이벤트 컴포넌트.
 *
 * 판정 시스템이 이 컴포넌트를 생성하고,
 * 스코어 시스템이 이를 읽고 제거.
 * 한 프레임에 여러 판정이 발생할 수 있으므로
 * 판정당 별도 엔티티 생성.
 */
data class JudgmentResultComponent(
    /** 판정 결과 (PERFECT, GREAT, GOOD, MISS) */
    val judgment: Judgment,

    /** 판정된 노트의 레인 (0–3) */
    val lane: Int,

    /** 판정된 노트의 시간 (ms, 디버깅용) */
    val timeMs: Long
) : Component
