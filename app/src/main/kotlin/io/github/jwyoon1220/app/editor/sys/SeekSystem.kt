package io.github.jwyoon1220.app.editor.sys

import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.editor.comp.SeekComp
import io.github.jwyoon1220.engine.ecs.EcsSystem
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.World

class SeekSystem(
    private val ctx: GameContext,
    private val entity: Long,
) : EcsSystem {

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) {
        val seek = world.require<SeekComp>(entity)
        if (seek.pendingSeekMs < 0L) return

        val now = System.currentTimeMillis()
        if (now - seek.lastSeekTimeMs > 66) {
            ctx.videoBackground.seek(seek.pendingSeekMs)
            seek.lastSeekTimeMs = now
            seek.pendingSeekMs = -1L
        }
    }
}
