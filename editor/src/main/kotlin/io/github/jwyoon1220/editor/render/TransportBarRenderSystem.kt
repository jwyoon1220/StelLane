package io.github.jwyoon1220.editor.render

import io.github.jwyoon1220.engine.FontRegistry
import io.github.jwyoon1220.editor.EditorUtils
import io.github.jwyoon1220.editor.comp.*
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand

/** 상단 트랜스포트 바 (재생/정지/녹음 버튼, 시간 표시, 모드/도구/퀀타이즈). */
class TransportBarRenderSystem(private val entity: Long) : RenderProducer {

    private val titleFont  = FontRegistry.semiBold(17f)
    private val timeFont   = FontRegistry.semiBold(22f)
    private val labelFont  = FontRegistry.regular(13f)
    private val hintFont   = FontRegistry.light(11f)

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit

    override fun produce(world: World, out: MutableList<RenderCommand>) {
        val layout = world.require<LayoutComp>(entity)
        val pb     = world.require<PlaybackComp>(entity)
        val st     = world.require<EditorStateComp>(entity)
        val q      = world.require<QuantizeComp>(entity)
        val tl     = world.require<TimelineViewComp>(entity)

        val w  = layout.designW
        val hh = layout.headerH
        val t  = pb.currentTimeMs
        val timeStr   = EditorUtils.formatTime(t)
        val isPlaying = pb.isPlaying
        val isRec     = pb.isRecording
        val mode      = st.mode
        val div       = q.division
        val qOn       = q.enabled
        val zoom      = tl.visibleMs / 1000
        val unsaved   = st.unsaved

        out.add(RenderCommand.LegacyDrawContext {
            val g = this

            // 배경
            g.fillLinearGradient(0f, 0f, w.toFloat(), hh.toFloat(),
                0f, 0f, 0f, hh.toFloat(),
                RenderColor.of(30, 16, 60, 250), RenderColor.of(18, 10, 38, 250))
            g.renderColor = RenderColor.of(100, 60, 200, 80)
            g.drawLine(0, hh - 1, w, hh - 1)

            // ── 왼쪽: 곡 제목 ──────────────────────────────────────────────────
            g.font = titleFont; g.renderColor = RenderColor.of(180, 140, 255)
            g.drawString("✏", 14f, 32f)

            // ── 중앙: 시간 + 트랜스포트 버튼 ─────────────────────────────────
            val cx = w / 2
            g.font = timeFont; g.renderColor = RenderColor.of(230, 220, 255)
            val tw = g.measureStringWidth(timeStr)
            g.drawString(timeStr, (cx - tw / 2).toFloat(), 33f)

            // 재생 상태 점
            val dot = if (isPlaying) "▶" else "⏸"
            g.font = labelFont; g.renderColor = if (isPlaying) RenderColor.of(80, 255, 120) else RenderColor.of(160, 140, 200)
            g.drawString(dot, (cx - tw / 2 - 22).toFloat(), 30f)

            // 녹음 표시
            if (isRec) {
                g.renderColor = RenderColor.of(255, 60, 60)
                g.fillOval(cx + (tw / 2 + 8).toInt(), 16, 8, 8)
            }

            // 저장 안 됨 표시
            if (unsaved) {
                g.renderColor = RenderColor.of(255, 80, 80)
                g.fillOval(w - 10, 10, 6, 6)
            }

            // ── 오른쪽: 모드 + 도구 + 퀀타이즈 ──────────────────────────────
            g.font = hintFont
            val modeStr  = if (mode == EditorMode.NOTE) "♩ NOTE" else "田 DECOR"
            val quantStr = if (qOn) "1/$div" else "Free"
            val statusStr = "$modeStr  ·  $quantStr  ·  ${zoom}s"
            g.renderColor = RenderColor.of(130, 110, 180)
            g.drawStringRight(statusStr, (w - 14).toFloat(), 30f)
        })
    }
}
