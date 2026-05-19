package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.engine.ecs.EcsSystem
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.render.RenderCommand
import io.github.jwyoon1220.engine.render.RenderColor

/**
 * 게임플레이 렌더링 시스템.
 *
 * NoteComponent와 ScoreComponent를 읽고
 * 렌더 커맨드를 생성합니다.
 *
 * 현재는 스켈레톤. 실제 렌더링 로직은 레거시 PlayState에서 복사.
 */
class PlayRenderSystem : EcsSystem, RenderProducer {

    private var lastCommands = mutableListOf<RenderCommand>()

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) {
        // Render 패스는 produce()에서 수행
    }

    override fun produce(world: World, out: MutableList<RenderCommand>) {
        out.clear()

        // 모든 활성 노트 렌더링
        val notes = world.entitiesWith<NoteComponent>()
        for (entity in notes) {
            val note = world.get<NoteComponent>(entity) ?: continue
            if (note.state == NoteState.PENDING) continue // 화면에 보이지 않음

            // 노트 위치 계산 (생략, 실제 구현 필요)
            // val y = calculateNoteY(note.timeMs, currentTimeMs)
            // out.add(RenderCommand.FillRect(x, y, w, h, RenderColor.WHITE))
        }

        // 점수/콤보 렌더링
        val scoreEntities = world.entitiesWith<ScoreComponent>()
        for (entity in scoreEntities) {
            val score = world.get<ScoreComponent>(entity) ?: continue

            // 점수 텍스트 (생략, 실제 구현 필요)
            // out.add(RenderCommand.DrawText(score.toString(), x, y, color))
        }

        lastCommands = out.toMutableList()
    }
}
