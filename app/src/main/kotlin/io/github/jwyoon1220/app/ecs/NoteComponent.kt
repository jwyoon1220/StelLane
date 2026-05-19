package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.engine.ecs.Component
import io.github.jwyoon1220.core.data.NoteType

/**
 * 게임 화면에 표시되는 노트 엔티티.
 *
 * 스폰 시간부터 판정/제거까지 노트의 전체 생명주기를 추적.
 * ECS 세계의 중심 엔티티.
 */
data class NoteComponent(
    /** 레인 (0–3) */
    val lane: Int,

    /** 노트 헤드 타이밍 (ms) */
    val timeMs: Long,

    /** 노트 테일 타이밍 (SHORT은 timeMs와 동일) */
    val endMs: Long,

    /** 노트 타입 (SHORT, LONG) */
    val type: NoteType,

    /** 현재 상태: PENDING(스폰 대기) → ACTIVE(화면 표시) → JUDGED(판정 완료) */
    val state: NoteState = NoteState.PENDING
) : Component

enum class NoteState {
    PENDING,  // 스폰 대기 (아직 noteQueue에 있음)
    ACTIVE,   // 화면 표시 중 (판정 대기)
    JUDGED    // 판정 완료 (제거 대기)
}
