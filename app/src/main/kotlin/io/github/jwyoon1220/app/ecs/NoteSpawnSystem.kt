package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.engine.ecs.EcsSystem
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.core.data.Note
import java.util.ArrayDeque

/**
 * 노트 스폰 시스템.
 *
 * noteQueue에서 타이밍에 맞춰 노트를 뽑아
 * ECS 세계에 NoteComponent 엔티티로 생성합니다.
 *
 * 스폰 조건: (note.time - currentTimeMs) < SPAWN_AHEAD_MS
 */
class NoteSpawnSystem(
    private val notes: List<Note>,
    private val spawnAheadMs: Long = 1200L
) : EcsSystem {

    private val noteQueue = ArrayDeque<Note>()
    private var nextNoteIndex = 0

    init {
        // 노트를 시간순으로 정렬해 큐에 추가
        val sortedNotes = notes.sortedBy { it.time }
        noteQueue.addAll(sortedNotes)
    }

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) {
        // currentTimeMs는 별도 컴포넌트로 추적하거나, lastInput으로부터 계산
        // 지금은 world에 GameTimeComponent가 있다고 가정
        // TODO: GameTimeComponent 또는 별도 메서드로 현재 시간 전달
        // 임시: input.frameTimeNs를 사용 (부정확함)
        val currentTimeMs = input.frameTimeNs / 1_000_000L

        // 스폰 조건 확인
        while (noteQueue.isNotEmpty()) {
            val nextNote = noteQueue.peek() ?: break
            if (nextNote.time - currentTimeMs > spawnAheadMs) break

            // 스폰!
            noteQueue.poll()
            val noteEntity = world.create()
            world.set(noteEntity, NoteComponent(
                lane = nextNote.lane,
                timeMs = nextNote.time,
                endMs = nextNote.endTime ?: nextNote.time,
                type = nextNote.type,
                state = NoteState.ACTIVE
            ))
        }

        // 화면을 지난 노트 제거 (렌더링 최적화)
        // TODO: 일정 시간 지난 노트 제거
    }
}
