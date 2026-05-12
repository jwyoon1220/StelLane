package io.github.jwyoon1220.app

import io.github.jwyoon1220.engine.DrawContext
import java.awt.Color
import kotlin.math.max
import kotlin.math.min

/**
 * PlayState 전용 커스텀 렌더러.
 */
class PlayCustomRenderer(
    private val shader: PlayCustomShader = PlayCustomShader()
) {
    companion object {
        private val COLOR_SHORT_TOP = Color(255, 245, 170)
        private val COLOR_SHORT_BOTTOM = Color(255, 210, 80)
        private val COLOR_SHORT_BORDER = Color(255, 250, 210)

        private val COLOR_LONG_BODY_TOP = Color(170, 95, 255, 170)
        private val COLOR_LONG_BODY_BOTTOM = Color(120, 60, 220, 170)
        private val COLOR_LONG_HEAD = Color(205, 140, 255)
        private val COLOR_LONG_BORDER = Color(235, 205, 255)
    }

    fun renderLaneEffects(
        g: DrawContext,
        lanesL: Int,
        laneWidth: Int,
        laneCount: Int,
        screenH: Int,
        laneHeld: BooleanArray
    ) {
        shader.renderLaneGlow(g, lanesL, laneWidth, laneCount, screenH, laneHeld)
    }

    fun renderNotes(
        g: DrawContext,
        lanesL: Int,
        laneWidth: Int,
        hitLineY: Int,
        scrollSpeed: Float,
        nowMs: Double,
        count: Int,
        soaLane: IntArray,
        soaTimeMs: LongArray,
        soaEndMs: LongArray,
        soaIsLong: BooleanArray,
        soaActive: BooleanArray,
        soaHeld: BooleanArray
    ) {
        val headW = (laneWidth - 10).toFloat()
        val bodyW = (laneWidth - 28).toFloat()
        val hlF = hitLineY.toFloat()

        for (i in 0 until count) {
            if (!soaActive[i] && !soaHeld[i]) continue
            val lx = lanesL + soaLane[i] * laneWidth
            val lxF = lx.toFloat()
            val noteTopYF = hlF - ((soaTimeMs[i] - nowMs) * scrollSpeed / 1000.0).toFloat()

            if (!soaIsLong[i]) {
                val noteX = lxF + 5f
                val noteY = noteTopYF - 18f
                g.fillLinearGradient(
                    x = noteX, y = noteY, w = headW, h = 18f,
                    x0 = noteX, y0 = noteY, x1 = noteX, y1 = noteY + 18f,
                    startColor = COLOR_SHORT_TOP,
                    endColor = COLOR_SHORT_BOTTOM
                )
                g.color = COLOR_SHORT_BORDER
                g.drawRoundRect(noteX, noteY, headW, 18f, 6f)
                continue
            }

            val endTopYF = hlF - ((soaEndMs[i] - nowMs) * scrollSpeed / 1000.0).toFloat()
            val bodyTopF = min(noteTopYF - 18f, endTopYF)
            val bodyBtmF = if (soaHeld[i]) hlF else max(noteTopYF, endTopYF)
            val bodyHF = bodyBtmF - bodyTopF
            if (bodyHF > 0f) {
                val bodyX = lxF + 14f
                g.fillLinearGradient(
                    x = bodyX, y = bodyTopF, w = bodyW, h = bodyHF,
                    x0 = bodyX, y0 = bodyTopF, x1 = bodyX, y1 = bodyTopF + bodyHF,
                    startColor = COLOR_LONG_BODY_TOP,
                    endColor = COLOR_LONG_BODY_BOTTOM
                )
            }

            val headX = lxF + 5f
            val headY = noteTopYF - 18f
            g.color = COLOR_LONG_HEAD
            g.fillRoundRect(headX, headY, headW, 18f, 6f)
            g.color = COLOR_LONG_BORDER
            g.drawRoundRect(headX, headY, headW, 18f, 6f)
        }
    }

    fun renderJudgmentEffect(
        g: DrawContext,
        lanesL: Int,
        totalWidth: Int,
        hitLineY: Int,
        fadeMs: Long,
        maxFadeMs: Long,
        judgmentColor: Color
    ) {
        shader.renderJudgmentPulse(g, lanesL, totalWidth, hitLineY, fadeMs, maxFadeMs, judgmentColor)
    }
}
