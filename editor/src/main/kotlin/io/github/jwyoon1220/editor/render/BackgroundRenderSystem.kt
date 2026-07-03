package io.github.jwyoon1220.editor.render

import io.github.jwyoon1220.editor.comp.LayoutComp
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand

/** 전체 배경 및 콘텐츠 영역 구분선. */
class BackgroundRenderSystem(private val entity: Long) : RenderProducer {

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit

    override fun produce(world: World, out: MutableList<RenderCommand>) {
        val layout = world.require<LayoutComp>(entity)
        val w = layout.designW; val h = layout.designH

        out.add(RenderCommand.LegacyDrawContext {
            val g = this

            // 전체 배경
            g.renderColor = RenderColor.of(7, 5, 14)
            g.fillRect(0, 0, w, h)

            // 콘텐츠 영역 (헤더 아래, 타임라인 위) — 좌측 패널 외 영역 미묘한 그라데이션
            val cy = layout.contentY; val ch = layout.contentH; val pw = layout.leftPanelW
            g.fillLinearGradient(
                pw.toFloat(), cy.toFloat(), (w - pw).toFloat(), ch.toFloat(),
                pw.toFloat(), cy.toFloat(), w.toFloat(), 0f,
                RenderColor.of(10, 7, 20), RenderColor.of(8, 5, 16)
            )

            // 타임라인 영역 배경
            val tlY = layout.tlAreaY; val tlH = h - tlY
            g.renderColor = RenderColor.of(9, 6, 18)
            g.fillRect(0, tlY, w, tlH)

            // 헤더-콘텐츠 구분선
            g.renderColor = RenderColor.of(90, 55, 180, 60)
            g.drawLine(0, cy, w, cy)
        })
    }
}
