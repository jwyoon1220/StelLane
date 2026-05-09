package io.github.jwyoon1220.core.data

// 에디터에서 수정 가능한 노트 (Jackson 직렬화 불필요 — Chart로 변환 후 저장)
data class MutableNote(
    var time: Long,
    var lane: Int,
    var type: NoteType,
    var endTime: Long? = null
)

// 에디터에서 수정 가능한 채보
class MutableChart(
    var offsetMs: Long = 0L,
    val notes: MutableList<MutableNote> = mutableListOf()
) {
    fun toChart(): Chart = Chart(
        offsetMs = offsetMs,
        notes = notes.map { Note(it.time, it.lane, it.type, it.endTime) }
    )
}
