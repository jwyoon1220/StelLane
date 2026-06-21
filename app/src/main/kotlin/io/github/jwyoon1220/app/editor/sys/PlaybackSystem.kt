package io.github.jwyoon1220.app.editor.sys

import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.editor.comp.DecorationRendererComp
import io.github.jwyoon1220.app.editor.comp.PlaybackComp
import io.github.jwyoon1220.app.editor.comp.TimelineViewComp
import io.github.jwyoon1220.engine.ecs.EcsSystem
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.World

class PlaybackSystem(
    private val ctx: GameContext,
    private val entity: Long,
    private val offsetMs: Long,
) : EcsSystem {

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) {
        val pb  = world.require<PlaybackComp>(entity)
        val tl  = world.require<TimelineViewComp>(entity)
        val dec = world.get<DecorationRendererComp>(entity)

        // 비디오 시간 동기화 (프레임당 1회)
        val frameId = ctx.videoBackground.getFrameId()
        if (frameId != pb.lastCacheFrameId || pb.lastCacheFrameId == -1L) {
            pb.currentTimeMs = ctx.videoBackground.getSmoothTimeMs() - offsetMs
            pb.lastCacheFrameId = frameId
        }
        val t = pb.currentTimeMs

        // 오디오 볼륨 페이드
        if (pb.isPlaying) {
            dec?.renderer?.computeTargetVolumePercent(t)?.let {
                ctx.videoBackground.setTargetVolumePercent(it)
            }
        }

        // 재생 중 타임라인 자동 스크롤
        if (pb.isPlaying) {
            if (t > tl.scrollMs + tl.visibleMs * 0.9) {
                tl.scrollMs = t - (tl.visibleMs * 0.1).toLong()
            } else if (t < tl.scrollMs) {
                tl.scrollMs = (t - tl.visibleMs * 0.1).toLong()
            }
        }
    }
}
