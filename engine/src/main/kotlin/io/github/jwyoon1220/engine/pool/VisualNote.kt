package io.github.jwyoon1220.engine.pool

import io.github.jwyoon1220.core.data.NoteType

/**
 * 풀에서 재사용되는 가변 노트 객체.
 * GC 스파이크 방지를 위해 new 대신 ObjectPool에서 acquire합니다.
 */
class VisualNote {
    var lane: Int       = 0
    var timeMs: Long    = 0L
    var endTimeMs: Long = 0L
    var type: NoteType  = NoteType.SHORT
    var y: Float        = 0f
    var endY: Float     = 0f
    var active: Boolean = false
    var held: Boolean   = false
}
