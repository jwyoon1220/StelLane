package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.engine.ecs.Component

/**
 * 게임 세션의 차트 메타데이터 싱글톤 컴포넌트.
 *
 * 여러 시스템이 차트 정보에 접근 가능하도록
 * 차트 엔티티 하나에만 할당됨.
 */
data class ChartComponent(
    /** 전체 노트 개수 */
    val totalNotes: Int,

    /** BPM */
    val bpm: Float,

    /** 오디오 오프셋 (ms) */
    val offsetMs: Long
) : Component
