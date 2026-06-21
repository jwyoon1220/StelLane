package io.github.jwyoon1220.app.editor.render

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.editor.comp.*
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderCommand
import java.awt.Color

/** 좌측 패널 — 프로젝트(장식 목록) + 속성 패널. */
class LeftPanelRenderSystem(private val entity: Long) : RenderProducer {

    private val headerFont = FontLoader.semiBold(14f)
    private val bodyFont   = FontLoader.regular(12f)
    private val hintFont   = FontLoader.light(11f)

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit

    override fun produce(world: World, out: MutableList<RenderCommand>) {
        val layout = world.require<LayoutComp>(entity)
        val st     = world.require<EditorStateComp>(entity)
        val ds     = world.require<DecorationSelectionComp>(entity)
        val dec    = world.require<DecorationRendererComp>(entity)

        val pw = layout.leftPanelW
        val py = layout.contentY
        val ph = layout.contentH
        val decorations = dec.decorations.toList()
        val selIdx = ds.selectedIdx
        val mode   = st.mode

        out.add(RenderCommand.LegacyDrawContext {
            val g = this

            // 패널 배경
            g.fillLinearGradient(0f, py.toFloat(), pw.toFloat(), ph.toFloat(),
                0f, 0f, pw.toFloat(), 0f,
                Color(16, 11, 30, 235), Color(10, 7, 20, 200))
            g.color = Color(70, 48, 120, 70)
            g.drawLine(pw, py, pw, py + ph)

            var ty = py + 14

            // PROJECT 섹션 헤더
            g.font = headerFont; g.color = Color(160, 120, 240)
            g.drawString("PROJECT", 10f, ty.toFloat()); ty += 18
            g.color = Color(45, 35, 70); g.drawLine(8, ty - 4, pw - 8, ty - 4); ty += 6

            // 장식 목록
            g.font = bodyFont
            if (mode == EditorMode.DECOR) {
                if (decorations.isEmpty()) {
                    g.color = Color(80, 65, 110); g.drawString("장식 없음", 12f, ty.toFloat()); ty += 18
                } else {
                    for ((idx, d) in decorations.withIndex()) {
                        val rowH = 20
                        val isSelected = idx == selIdx
                        if (isSelected) {
                            g.color = Color(90, 55, 160, 120)
                            g.fillRect(4, ty - 13, pw - 10, rowH)
                        }
                        val hue = (idx * 53) % 360
                        g.color = Color.getHSBColor(hue / 360f, 0.65f, if (isSelected) 1f else 0.7f)
                        g.drawString("▣", 10f, ty.toFloat())
                        g.color = if (isSelected) Color(230, 200, 255) else Color(180, 160, 220)
                        val label = d.id.ifEmpty { "(decor $idx)" }.let { if (it.length > 18) it.take(17) + "…" else it }
                        g.drawString(label, 26f, ty.toFloat())
                        ty += rowH
                        if (ty > py + ph - 80) { g.color = Color(80, 65, 110); g.drawString("…", 12f, ty.toFloat()); break }
                    }
                }
            } else {
                g.color = Color(80, 65, 110)
                g.drawString("田 장식 모드 비활성", 12f, ty.toFloat()); ty += 16
                g.font = hintFont; g.color = Color(60, 50, 90)
                g.drawString("Ctrl+Shift+D 로 전환", 12f, ty.toFloat()); ty += 18
            }

            ty += 6
            g.color = Color(45, 35, 70); g.drawLine(8, ty - 2, pw - 8, ty - 2); ty += 10

            // PROPERTIES 섹션
            g.font = headerFont; g.color = Color(160, 120, 240)
            g.drawString("PROPERTIES", 10f, ty.toFloat()); ty += 18
            g.color = Color(45, 35, 70); g.drawLine(8, ty - 4, pw - 8, ty - 4); ty += 6

            val sel = decorations.getOrNull(selIdx)
            if (sel == null) {
                g.font = bodyFont; g.color = Color(70, 55, 100)
                g.drawString("선택 없음", 12f, ty.toFloat())
            } else {
                g.font = bodyFont
                fun row(label: String, value: String) {
                    g.color = Color(110, 90, 165); g.drawString(label, 12f, ty.toFloat())
                    g.color = Color(210, 195, 245); g.drawString(value, 88f, ty.toFloat())
                    ty += 17
                }
                g.color = Color(180, 140, 255); g.font = headerFont
                g.drawString(sel.id.ifEmpty { "(unnamed)" }, 12f, ty.toFloat()); ty += 18
                g.font = bodyFont
                row("time",    "${sel.timeMs} ms")
                row("dur",     "${sel.durationMs} ms")
                row("x / y",   "%.3f / %.3f".format(sel.x, sel.y))
                row("w / h",   "${sel.width} / ${sel.height}")
                row("opacity", "%.2f".format(sel.opacity))
                row("rot",     "%.1f°".format(sel.rotation))
                row("depth",   "${sel.depth}")
                row("fx",      "${sel.effects.size}개")
                ty += 4
                g.font = hintFont; g.color = Color(65, 52, 95)
                g.drawString("E: 편집   Del: 삭제", 12f, ty.toFloat())
            }

            // 하단 힌트
            g.font = hintFont; g.color = Color(55, 44, 80)
            g.drawString("총 ${decorations.size}개", 12f, (py + ph - 8).toFloat())
        })
    }
}
