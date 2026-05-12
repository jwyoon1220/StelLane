package io.github.jwyoon1220.app

import io.github.jwyoon1220.engine.DrawContext
import java.awt.Color

/**
 * PlayState 전용 화면 효과 셰이더(gradient 기반).
 */
class PlayCustomShader {
    fun renderLaneGlow(
        g: DrawContext,
        lanesL: Int,
        laneWidth: Int,
        laneCount: Int,
        screenH: Int,
        laneHeld: BooleanArray
    ) {
        for (i in 0 until laneCount) {
            val lx = (lanesL + i * laneWidth).toFloat()
            val held = laneHeld.getOrElse(i) { false }
            val baseAlpha = if (held) 80 else 36
            g.fillLinearGradient(
                x = lx,
                y = 0f,
                w = laneWidth.toFloat(),
                h = screenH.toFloat(),
                x0 = lx,
                y0 = 0f,
                x1 = lx,
                y1 = screenH.toFloat(),
                startColor = Color(110, 80, 220, 0),
                endColor = Color(120, 95, 255, baseAlpha)
            )
        }
    }

    fun renderJudgmentPulse(
        g: DrawContext,
        lanesL: Int,
        totalWidth: Int,
        hitLineY: Int,
        fadeMs: Long,
        maxFadeMs: Long,
        baseColor: Color
    ) {
        if (fadeMs <= 0L || maxFadeMs <= 0L) return
        val t = (fadeMs.toFloat() / maxFadeMs.toFloat()).coerceIn(0f, 1f)
        val cx = lanesL + totalWidth / 2f
        val cy = hitLineY.toFloat()
        val radius = 120f + (1f - t) * 90f
        val inner = Color(baseColor.red, baseColor.green, baseColor.blue, (100f * t).toInt().coerceIn(0, 255))
        val outer = Color(baseColor.red, baseColor.green, baseColor.blue, 0)
        g.fillRadialGradient(
            x = lanesL.toFloat(),
            y = (hitLineY - 180).toFloat(),
            w = totalWidth.toFloat(),
            h = 260f,
            cx = cx,
            cy = cy,
            inR = 8f,
            outR = radius,
            innerColor = inner,
            outerColor = outer
        )
    }
}
