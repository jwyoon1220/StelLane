package io.github.jwyoon1220.app.render

import io.github.jwyoon1220.app.Const
import io.github.jwyoon1220.app.ecs.PlayScene
import io.github.jwyoon1220.engine.GlQuadBatchRenderer
import io.github.jwyoon1220.engine.Renderer as EngineRenderer
import io.github.jwyoon1220.engine.render.RenderColor
import kotlin.math.max
import kotlin.math.min

open class NoteRenderer {

    companion object {
        private val COLOR_GL_LANE_FADE_TOP   = RenderColor.of(96, 70, 210, 0)
        private val COLOR_GL_LANE_GLOW_HELD  = RenderColor.of(128, 98, 255, 90)
        private val COLOR_GL_LANE_GLOW_NORM  = RenderColor.of(128, 98, 255, 42)

        private val COLOR_GL_SHORT_TOP     = RenderColor.of(255, 248, 190, 255)
        private val COLOR_GL_SHORT_BTM     = RenderColor.of(255, 210, 80, 255)
        private val COLOR_GL_SHORT_BORDER  = RenderColor.of(255, 252, 220, 255)
        private val COLOR_GL_SHORT_BORDER2 = RenderColor.of(255, 230, 140, 220)

        private val COLOR_GL_LONG_BODY_TOP  = RenderColor.of(190, 130, 255, 185)
        private val COLOR_GL_LONG_BODY_BTM  = RenderColor.of(120, 60, 220, 185)
        private val COLOR_GL_LONG_HEAD_TOP  = RenderColor.of(218, 165, 255, 255)
        private val COLOR_GL_LONG_HEAD_BTM  = RenderColor.of(175, 110, 250, 255)
        private val COLOR_GL_LONG_BORDER    = RenderColor.of(238, 220, 255, 255)

        private const val NOTE_BORDER_THICKNESS = 1.5f
    }

    open fun render(renderer: GlQuadBatchRenderer, state: PlayScene) {
        val laneAlpha = state.laneAlpha
        if (laneAlpha <= 0f) return

        val h    = EngineRenderer.DESIGN_H
        val hl   = (h * Const.HIT_LINE_RATIO).toInt()
        val lanesL = (EngineRenderer.DESIGN_W - Const.TOTAL_WIDTH) / 2
        val nowD = if (state.phase == PlayScene.Phase.READY)
            state.readyElapsedMs - Const.READY_DURATION_MS
        else
            state.ctx.videoBackground.getSmoothTimeDouble() - state.chart.offsetMs

        for (i in 0 until Const.LANE_COUNT) {
            val laneX = (lanesL + i * Const.LANE_WIDTH).toFloat()
            val glowColor = if (state.laneHeld[i]) COLOR_GL_LANE_GLOW_HELD else COLOR_GL_LANE_GLOW_NORM
            val alphaAdjusted = glowColor.withAlpha((glowColor.a * laneAlpha).toInt())
            renderer.drawGradientRect(
                x = laneX, y = 0f,
                w = Const.LANE_WIDTH.toFloat(), h = EngineRenderer.DESIGN_H.toFloat(),
                topLeft     = COLOR_GL_LANE_FADE_TOP,
                topRight    = COLOR_GL_LANE_FADE_TOP,
                bottomRight = alphaAdjusted,
                bottomLeft  = alphaAdjusted
            )
        }

        synchronized(state.notesLock) {
            val count = state.soaSize
            for (i in 0 until count) {
                if (!state.soaActive[i] && !state.soaHeld[i]) continue

                val laneX = (lanesL + state.soaLane[i] * Const.LANE_WIDTH).toFloat()
                val noteTop = hl - ((state.soaTimeMs[i] - nowD) * Const.SCROLL_SPEED / 1000.0).toFloat()
                val headX = laneX + 5f
                val headY = noteTop - 18f
                val headW = (Const.LANE_WIDTH - 10).toFloat()
                val headH = 18f

                if (!state.soaIsLong[i]) {
                    renderer.drawGradientRect(
                        x = headX, y = headY, w = headW, h = headH,
                        topLeft     = COLOR_GL_SHORT_TOP, topRight    = COLOR_GL_SHORT_TOP,
                        bottomRight = COLOR_GL_SHORT_BTM, bottomLeft  = COLOR_GL_SHORT_BTM
                    )
                    renderer.drawRect(headX, headY, headW, NOTE_BORDER_THICKNESS, COLOR_GL_SHORT_BORDER)
                    renderer.drawRect(headX, headY + headH - NOTE_BORDER_THICKNESS,
                        headW, NOTE_BORDER_THICKNESS, COLOR_GL_SHORT_BORDER2)
                } else {
                    val endTop = hl - ((state.soaEndMs[i] - nowD) * Const.SCROLL_SPEED / 1000.0).toFloat()
                    val bodyTop    = min(headY, endTop)
                    val bodyBottom = if (state.soaHeld[i]) hl.toFloat() else max(noteTop, endTop)
                    val bodyH = bodyBottom - bodyTop
                    if (bodyH > 0f) {
                        renderer.drawGradientRect(
                            x = laneX + 14f, y = bodyTop,
                            w = (Const.LANE_WIDTH - 28).toFloat(), h = bodyH,
                            topLeft     = COLOR_GL_LONG_BODY_TOP, topRight    = COLOR_GL_LONG_BODY_TOP,
                            bottomRight = COLOR_GL_LONG_BODY_BTM, bottomLeft  = COLOR_GL_LONG_BODY_BTM
                        )
                    }
                    renderer.drawGradientRect(
                        x = headX, y = headY, w = headW, h = headH,
                        topLeft     = COLOR_GL_LONG_HEAD_TOP, topRight    = COLOR_GL_LONG_HEAD_TOP,
                        bottomRight = COLOR_GL_LONG_HEAD_BTM, bottomLeft  = COLOR_GL_LONG_HEAD_BTM
                    )
                    renderer.drawRect(headX, headY, headW, NOTE_BORDER_THICKNESS, COLOR_GL_LONG_BORDER)
                }
            }
        }

        if (state.judgmentFadeMs > 0 && Const.JUDGE_FADE_MS > 0L) {
            val t = (state.judgmentFadeMs.toFloat() / Const.JUDGE_FADE_MS.toFloat()).coerceIn(0f, 1f)
            val pulseAlpha = (110f * t).toInt().coerceIn(0, 255)
            val jc = state.judgmentColor
            renderer.drawGradientRect(
                x = lanesL.toFloat(), y = hl.toFloat() - 106f,
                w = Const.TOTAL_WIDTH.toFloat(), h = 180f,
                topLeft     = jc.withAlpha(0),
                topRight    = jc.withAlpha(0),
                bottomRight = jc.withAlpha(pulseAlpha),
                bottomLeft  = jc.withAlpha(pulseAlpha)
            )
        }
    }
}
