package io.github.jwyoon1220.editor.render

import io.github.jwyoon1220.engine.FontRegistry
import io.github.jwyoon1220.editor.comp.*
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand

/** 좌측 패널 — 프로젝트(장식 목록) + 속성 패널. */
class LeftPanelRenderSystem(private val entity: Long) : RenderProducer {

    private val headerFont = FontRegistry.semiBold(14f)
    private val bodyFont   = FontRegistry.regular(12f)
    private val hintFont   = FontRegistry.light(11f)

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
                RenderColor.of(16, 11, 30, 235), RenderColor.of(10, 7, 20, 200))
            g.renderColor = RenderColor.of(70, 48, 120, 70)
            g.drawLine(pw, py, pw, py + ph)

            var ty = py + 14

            // PROJECT 섹션 헤더
            g.font = headerFont; g.renderColor = RenderColor.of(160, 120, 240)
            g.drawString("PROJECT", 10f, ty.toFloat()); ty += 18
            g.renderColor = RenderColor.of(45, 35, 70); g.drawLine(8, ty - 4, pw - 8, ty - 4); ty += 6

            // 장식 목록
            g.font = bodyFont
            if (mode == EditorMode.DECOR) {
                if (decorations.isEmpty()) {
                    g.renderColor = RenderColor.of(80, 65, 110); g.drawString("장식 없음", 12f, ty.toFloat()); ty += 18
                } else {
                    for ((idx, d) in decorations.withIndex()) {
                        val rowH = 20
                        val isSelected = idx == selIdx
                        if (isSelected) {
                            g.renderColor = RenderColor.of(90, 55, 160, 120)
                            g.fillRect(4, ty - 13, pw - 10, rowH)
                        }
                        val hue = (idx * 53) % 360
                        g.renderColor = RenderColor.fromHSB(hue / 360f, 0.65f, if (isSelected) 1f else 0.7f)
                        g.drawString("▣", 10f, ty.toFloat())
                        g.renderColor = if (isSelected) RenderColor.of(230, 200, 255) else RenderColor.of(180, 160, 220)
                        val label = d.id.ifEmpty { "(decor $idx)" }.let { if (it.length > 18) it.take(17) + "…" else it }
                        g.drawString(label, 26f, ty.toFloat())
                        ty += rowH
                        if (ty > py + ph - 80) { g.renderColor = RenderColor.of(80, 65, 110); g.drawString("…", 12f, ty.toFloat()); break }
                    }
                }
            } else {
                g.renderColor = RenderColor.of(80, 65, 110)
                g.drawString("田 장식 모드 비활성", 12f, ty.toFloat()); ty += 16
                g.font = hintFont; g.renderColor = RenderColor.of(60, 50, 90)
                g.drawString("Ctrl+Shift+D 로 전환", 12f, ty.toFloat()); ty += 18
            }

            ty += 6
            g.renderColor = RenderColor.of(45, 35, 70); g.drawLine(8, ty - 2, pw - 8, ty - 2); ty += 10

            // PROPERTIES 섹션
            g.font = headerFont; g.renderColor = RenderColor.of(160, 120, 240)
            g.drawString("PROPERTIES", 10f, ty.toFloat()); ty += 18
            g.renderColor = RenderColor.of(45, 35, 70); g.drawLine(8, ty - 4, pw - 8, ty - 4); ty += 6

            val sel = decorations.getOrNull(selIdx)
            if (sel == null) {
                g.font = bodyFont; g.renderColor = RenderColor.of(70, 55, 100)
                g.drawString("선택 없음", 12f, ty.toFloat())
            } else {
                g.font = bodyFont
                fun row(label: String, value: String) {
                    g.renderColor = RenderColor.of(110, 90, 165); g.drawString(label, 12f, ty.toFloat())
                    g.renderColor = RenderColor.of(210, 195, 245); g.drawString(value, 88f, ty.toFloat())
                    ty += 17
                }
                g.renderColor = RenderColor.of(180, 140, 255); g.font = headerFont
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
                g.font = hintFont; g.renderColor = RenderColor.of(65, 52, 95)
                g.drawString("E: 편집   Del: 삭제", 12f, ty.toFloat())
            }

            // 하단 힌트
            g.font = hintFont; g.renderColor = RenderColor.of(55, 44, 80)
            g.drawString("총 ${decorations.size}개", 12f, (py + ph - 8).toFloat())
        })
    }
}
