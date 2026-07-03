package io.github.jwyoon1220.editor.render

import io.github.jwyoon1220.engine.FontRegistry
import io.github.jwyoon1220.editor.comp.*
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand
import java.awt.BasicStroke

/**
 * 프로그램 모니터 패널 — 비디오 뷰포트 테두리 + 장식 오버레이 + 선택 핸들.
 * 실제 비디오 텍스처는 EditorScene.renderCustomGl()에서 OpenGL로 그립니다.
 */
class ProgramMonitorRenderSystem(private val entity: Long) : RenderProducer {

    private val hintFont  = FontRegistry.light(12f)
    private val infoFont  = FontRegistry.regular(14f)
    private val labelFont = FontRegistry.regular(11f)

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit

    override fun produce(world: World, out: MutableList<RenderCommand>) {
        val layout = world.require<LayoutComp>(entity)
        val pb     = world.require<PlaybackComp>(entity)
        val st     = world.require<EditorStateComp>(entity)
        val ds     = world.require<DecorationSelectionComp>(entity)
        val dec    = world.require<DecorationRendererComp>(entity)

        val vpX  = layout.vpX;  val vpY  = layout.vpY
        val vpW  = layout.vpW;  val vpH  = layout.vpH
        val t    = pb.currentTimeMs
        val mode = st.mode
        val selIdx = ds.selectedIdx
        val decorations = dec.decorations.toList()
        val decorRend   = dec.renderer

        out.add(RenderCommand.LegacyDrawContext {
            val g = this

            if (vpW <= 0 || vpH <= 0) return@LegacyDrawContext

            // 뷰포트 어두운 배경 (비디오 없을 때 placeholder)
            g.renderColor = RenderColor.of(14, 10, 28)
            g.fillRect(vpX - 2, vpY - 2, vpW + 4, vpH + 4)

            if (dec.renderer == null) {
                g.renderColor = RenderColor.of(20, 15, 35)
                g.fillRect(vpX, vpY, vpW, vpH)
                g.renderColor = RenderColor.of(60, 50, 90); g.font = infoFont
                g.drawStringCentered("▶ 영상 없음", (vpX + vpW / 2).toFloat(), (vpY + vpH / 2).toFloat())
            }

            // 장식 오버레이 (NanoVG)
            if (decorRend != null) {
                g.save()
                g.setClip(vpX, vpY, vpW, vpH)
                g.translate(vpX.toDouble(), vpY.toDouble())
                g.scale(vpW / 1280.0, vpH / 720.0)
                decorRend.render(g, t, beforeNotes = true)
                decorRend.render(g, t, beforeNotes = false)
                decorRend.renderScreenEffects(g, t)
                g.restore()
            }

            // 장식 모드: 선택된 장식 바운딩 박스
            if (mode == EditorMode.DECOR && selIdx >= 0) {
                val d = decorations.getOrNull(selIdx)
                d?.let {
                    val sx = vpW / 1280f; val sy = vpH / 720f
                    val dw = if (d.width <= 1f) d.width * 1280f else d.width
                    val dh = if (d.height <= 1f) d.height * 720f else d.height
                    val fx = vpX + ((d.x * 1280f - d.pivotX * dw) * sx).toInt()
                    val fy = vpY + ((d.y * 720f  - d.pivotY * dh) * sy).toInt()
                    val fw = (dw * sx).toInt().coerceAtLeast(2)
                    val fh = (dh * sy).toInt().coerceAtLeast(2)

                    g.renderColor = RenderColor.of(210, 150, 255, 200)
                    g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(5f, 4f), 0f)
                    g.drawRect(fx, fy, fw, fh)
                    g.stroke = BasicStroke(1.5f)

                    // 코너 핸들
                    for ((hx, hy) in listOf(fx to fy, fx + fw to fy, fx to fy + fh, fx + fw to fy + fh)) {
                        g.renderColor = RenderColor.of(210, 150, 255); g.fillRect(hx - 4, hy - 4, 8, 8)
                        g.renderColor = RenderColor.of(0, 0, 0, 100);  g.drawRect(hx - 4, hy - 4, 8, 8)
                    }
                    g.renderColor = RenderColor.of(210, 150, 255, 180); g.font = labelFont
                    g.drawString(d.id.ifEmpty { "(decoration)" }, (fx + 2).toFloat(), (fy - 4).toFloat())
                }
            }

            // 뷰포트 테두리 강조
            g.renderColor = RenderColor.of(80, 55, 130, 60)
            g.drawRect(vpX, vpY, vpW, vpH)
        })
    }
}
