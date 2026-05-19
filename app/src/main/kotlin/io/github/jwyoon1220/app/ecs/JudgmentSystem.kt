package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.engine.ecs.EcsSystem
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.core.judgment.Judgment
import io.github.jwyoon1220.core.judgment.JudgmentSystem as LegacyJudgmentSystem

/**
 * 판정 시스템.
 *
 * 활성 노트와 레인 입력을 비교해 판정을 수행하고
 * JudgmentResultComponent를 생성합니다.
 *
 * 레거시 JudgmentSystem의 거리 계산을 재사용합니다.
 */
class JudgmentSystem : EcsSystem {

    // 각 레인에서 마지막으로 누른 입력 시간 추적 (더블탭 방지)
    private val lastInputTimeMs = LongArray(4) { -2000L }

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) {
        // currentTimeMs: 별도 컴포넌트에서 가져와야 함
        // 임시: frameTimeNs 사용 (부정확)
        val currentTimeMs = input.frameTimeNs / 1_000_000L

        // 활성 노트 모두 조회
        val activeNotes = world.entitiesWith<NoteComponent>()

        // 이번 프레임 레인 입력 처리
        for (laneEvent in input.laneEvents) {
            if (laneEvent.lane < 0 || laneEvent.lane >= 4) continue

            // 더블탭 방지: 같은 레인에서 100ms 이내 입력 무시
            if (currentTimeMs - lastInputTimeMs[laneEvent.lane] < 100) continue

            lastInputTimeMs[laneEvent.lane] = currentTimeMs

            // 해당 레인의 활성 노트 찾기
            var bestEntity = 0L
            var bestNote: NoteComponent? = null
            var bestDist = Long.MAX_VALUE

            for (entity in activeNotes) {
                val note = world.get<NoteComponent>(entity) ?: continue
                if (note.lane != laneEvent.lane || note.state != NoteState.ACTIVE) continue

                val dist = kotlin.math.abs(note.timeMs - currentTimeMs)
                if (dist < bestDist) {
                    bestDist = dist
                    bestEntity = entity
                    bestNote = note
                }
            }

            // 판정 수행
            if (bestNote != null && bestDist < LegacyJudgmentSystem.GOOD_MS) {
                val judgment = when {
                    bestDist <= LegacyJudgmentSystem.PERFECT_MS -> Judgment.PERFECT
                    bestDist <= LegacyJudgmentSystem.GREAT_MS   -> Judgment.GREAT
                    else                                         -> Judgment.GOOD
                }

                // 판정 결과 엔티티 생성
                val resultEntity = world.create()
                world.set(resultEntity, JudgmentResultComponent(
                    judgment = judgment,
                    lane = laneEvent.lane,
                    timeMs = bestNote.timeMs
                ))

                // 노트 상태 업데이트
                world.set(bestEntity, bestNote.copy(state = NoteState.JUDGED))
            }
        }

        // 미스 판정: 판정선을 지난 노트
        val missLineMs = currentTimeMs + LegacyJudgmentSystem.GOOD_MS
        for (entity in activeNotes) {
            val note = world.get<NoteComponent>(entity) ?: continue
            if (note.state != NoteState.ACTIVE || note.timeMs >= missLineMs) continue

            // 미스!
            val resultEntity = world.create()
            world.set(resultEntity, JudgmentResultComponent(
                judgment = Judgment.MISS,
                lane = note.lane,
                timeMs = note.timeMs
            ))

            world.set(entity, note.copy(state = NoteState.JUDGED))
        }
    }
}
