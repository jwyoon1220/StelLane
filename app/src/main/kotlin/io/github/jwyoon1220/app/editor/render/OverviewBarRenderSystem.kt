package io.github.jwyoon1220.app.editor.render

import io.github.jwyoon1220.app.editor.EditorUtils
import io.github.jwyoon1220.app.editor.comp.*
import io.github.jwyoon1220.core.data.MutableChart
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderCommand
import java.awt.Color
import java.awt.image.DataBufferInt

private val LANE_COLORS = arrayOf(
    Color(100, 180, 255), Color(100, 255, 160),
    Color(255, 200, 80),  Color(255, 120, 120)
)

/** 타임라인 하단 오버뷰(미니맵) 바. */
class OverviewBarRenderSystem(
    private val entity: Long,
    private val chart: MutableChart,
    private val notesLock: Any,
    private val offsetMs: Long,
    private val ctx: io.github.jwyoon1220.app.GameContext,
) : RenderProducer {

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit

    override fun produce(world: World, out: MutableList<RenderCommand>) {
        val layout = world.require<LayoutComp>(entity)
        val tl     = world.require<TimelineViewComp>(entity)
        val pb     = world.require<PlaybackComp>(entity)
        val ov     = world.require<OverviewComp>(entity)

        val barY  = layout.overviewY; val barH = layout.overviewBarH
        val barX  = layout.tlX;       val barW = layout.tlW
        val curT  = pb.currentTimeMs
        val visMs = tl.visibleMs;  val scrollMs = tl.scrollMs

        val totalRangeMs = EditorUtils.getTimelineRangeMs(chart, visMs, ctx.videoBackground.getLengthMs(), offsetMs, notesLock)
        val maxScrollMs  = (totalRangeMs - visMs).coerceAtLeast(0L)
        val selRatio = (visMs.toDouble() / totalRangeMs).coerceIn(0.05, 1.0)
        val selW     = (barW * selRatio).toInt().coerceIn(minOf(24, barW), barW)
        val selX     = if (maxScrollMs == 0L) barX
                       else barX + ((barW - selW) * (scrollMs.toDouble() / maxScrollMs)).toInt()
        val phRatio = (curT.coerceIn(0L, totalRangeMs).toDouble() / totalRangeMs)
        val phX     = barX + (barW * phRatio).toInt().coerceIn(0, barW)

        // 노트 수, 폭, 범위 변경 시에만 픽셀 재계산
        val noteCount = synchronized(notesLock) { chart.notes.size }
        if (noteCount != ov.cachedNoteCount || barW != ov.cachedWidth || totalRangeMs != ov.cachedRangeMs) {
            val imgW = barW.coerceAtLeast(1)
            var img  = ov.overviewImg
            if (img == null || img.width != imgW || img.height != barH) {
                img = java.awt.image.BufferedImage(imgW, barH, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                ov.overviewImg = img
            }
            val pixels = (img.raster.dataBuffer as DataBufferInt).data
            pixels.fill(0)
            val lineH = barH - 6
            synchronized(notesLock) {
                chart.notes.forEach { note ->
                    val nx = ((note.time.coerceIn(0L, totalRangeMs).toDouble() / totalRangeMs) * (imgW - 1)).toInt().coerceIn(0, imgW - 1)
                    val c  = LANE_COLORS[note.lane]
                    val argb = (130 shl 24) or (c.red shl 16) or (c.green shl 8) or c.blue
                    for (row in 3 until (3 + lineH)) pixels[row * imgW + nx] = argb
                }
            }
            ov.cachedNoteCount = noteCount; ov.cachedWidth = barW; ov.cachedRangeMs = totalRangeMs
        }

        val imgCapture = ov.overviewImg

        out.add(RenderCommand.LegacyDrawContext {
            val g = this

            // 배경
            g.color = Color(10, 8, 18, 235)
            g.fillRoundRect(barX, barY, barW, barH, 5, 5)
            g.color = Color(35, 28, 55, 160)
            g.drawRoundRect(barX, barY, barW, barH, 5, 5)

            // 노트 도트 이미지
            imgCapture?.let { g.drawImage(it, barX, barY) }

            // 현재 뷰 선택 핸들
            g.color = Color(100, 62, 200, 170)
            g.fillRoundRect(selX, barY + 2, selW, barH - 4, 4, 4)
            g.color = Color(200, 180, 255, 200)
            g.drawRoundRect(selX, barY + 2, selW, barH - 4, 4, 4)

            // 플레이헤드
            g.color = Color(240, 140, 255, 200)
            g.drawLine(phX, barY + 1, phX, barY + barH - 1)
        })
    }
}
