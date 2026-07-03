package io.github.jwyoon1220.editor.sys

import io.github.jwyoon1220.editor.comp.SeekComp
import io.github.jwyoon1220.engine.VideoBackground
import io.github.jwyoon1220.engine.ecs.EcsSystem
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.World

class SeekSystem(
    private val videoBackground: VideoBackground,
    private val entity: Long,
) : EcsSystem {

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) {
        val seek = world.require<SeekComp>(entity)
        if (seek.pendingSeekMs < 0L) return

        val now = System.currentTimeMillis()
        if (now - seek.lastSeekTimeMs > 66) {
            videoBackground.seek(seek.pendingSeekMs)
            seek.lastSeekTimeMs = now
            seek.pendingSeekMs = -1L
        }
    }
}
