package io.github.jwyoon1220.engine.ecs

/**
 * 모든 ECS 컴포넌트의 마커 인터페이스.
 *
 * 컴포넌트는 **순수 데이터**여야 합니다 — 로직을 포함하지 않습니다.
 * 가급적 `data class`로 구현하세요.
 *
 * 예:
 * ```kotlin
 * data class PositionComponent(val x: Float, val y: Float) : Component
 * data class NoteComponent(val lane: Int, val timeMs: Long) : Component
 * ```
 */
interface Component
