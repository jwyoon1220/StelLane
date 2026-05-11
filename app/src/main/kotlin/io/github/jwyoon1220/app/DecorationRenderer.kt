package io.github.jwyoon1220.app

import io.github.jwyoon1220.core.data.DecEffect
import io.github.jwyoon1220.core.data.Decoration
import io.github.jwyoon1220.core.data.DecorationData
import io.github.jwyoon1220.core.data.ScreenEffect
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin

/**
 * decoration.json 에 정의된 장식/화면 효과를 매 프레임 렌더링한다.
 *
 * 좌표계 전제: Graphics2D 는 1280×720 논리 공간 (RenderPanel 이 이미 변환).
 */
class DecorationRenderer(
    private val data: DecorationData,
    private val songDir: File
) {
    private val imageCache = HashMap<String, BufferedImage?>()

    private fun image(path: String): BufferedImage? =
        imageCache.getOrPut(path) {
            runCatching { ImageIO.read(File(songDir, path)) }.getOrNull()
        }

    // ── 이징 함수 ─────────────────────────────────────────────────────────────
    private fun ease(t: Float, easing: String): Float = when (easing) {
        "easeIn"    -> t * t
        "easeOut"   -> 1f - (1f - t) * (1f - t)
        "easeInOut" -> if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f
        else        -> t
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ── 메인 렌더 진입점 ──────────────────────────────────────────────────────
    /** 노트 렌더링 이전(depth<0)과 이후(depth≥0) 두 번 호출한다. */
    fun render(g: Graphics2D, currentMs: Long, beforeNotes: Boolean) {
        val active = data.decorations
            .filter { currentMs >= it.timeMs && currentMs < it.timeMs + it.durationMs }
            .sortedBy { it.depth }
            .filter { if (beforeNotes) it.depth < 0 else it.depth >= 0 }

        for (dec in active) {
            renderDecoration(g, dec, currentMs - dec.timeMs)
        }
    }

    /** 화면 전체 효과 (flashm vignette 등) — HUD 렌더링 전에 호출. */
    fun renderScreenEffects(g: Graphics2D, currentMs: Long) {
        for (eff in data.screenEffects) {
            if (currentMs < eff.timeMs || currentMs >= eff.timeMs + eff.durationMs) continue
            val elapsed = currentMs - eff.timeMs
            renderScreenEffect(g, eff, elapsed)
        }
    }

    // ── 개별 장식 렌더 ────────────────────────────────────────────────────────
    private fun renderDecoration(g: Graphics2D, dec: Decoration, elapsedMs: Long) {
        val img = if (dec.image.isNotEmpty()) image(dec.image) else null

        // 기저값에서 시작해 효과를 순서대로 적용
        var cx       = dec.x
        var cy       = dec.y
        var rotation = dec.rotation
        var scaleX   = dec.scaleX
        var scaleY   = dec.scaleY
        var opacity  = dec.opacity
        var tintR    = 255; var tintG = 255; var tintB = 255; var tintA = 0
        var shakeX   = 0f; var shakeY = 0f

        for (eff in dec.effects) {
            val effStart = eff.startMs
            if (elapsedMs < effStart) continue       // 아직 시작 안 함

            // 완료됐으면 t=1 (래치)
            val rawT = if (eff.durationMs <= 0L) 1f else
                ((elapsedMs - effStart).toFloat() / eff.durationMs).coerceIn(0f, 1f)
            val t = ease(rawT, eff.easing)

            when (eff.type) {
                "fadeIn"      -> opacity   = lerp(0f, dec.opacity, t)
                "fadeOut"     -> opacity   = lerp(dec.opacity, 0f, t)
                "opacityTo"   -> eff.toOpacity?.let  { opacity   = lerp(dec.opacity,   it, t) }
                "moveTo"      -> {
                    eff.toX?.let { cx = lerp(dec.x, it, t) }
                    eff.toY?.let { cy = lerp(dec.y, it, t) }
                }
                "rotateTo"    -> eff.toRotation?.let { rotation  = lerp(dec.rotation,  it, t) }
                "scaleTo"     -> {
                    eff.toScaleX?.let { scaleX = lerp(dec.scaleX, it, t) }
                    eff.toScaleY?.let { scaleY = lerp(dec.scaleY, it, t) }
                }
                "colorFilter" -> { tintR = eff.r; tintG = eff.g; tintB = eff.b; tintA = eff.a }
                "shake"       -> {
                    val phase = elapsedMs * eff.frequency / 1000.0 * 2.0 * PI
                    shakeX = (sin(phase) * eff.amplitude).toFloat()
                    shakeY = (sin(phase * 1.3 + 1.0) * eff.amplitude * 0.6f).toFloat()
                }
            }
        }

        if (opacity <= 0f) return
        img ?: return

        // 0~1 정규화 → 논리 픽셀 변환 (>1 은 레거시 절대 픽셀로 취급)
        val baseW = if (dec.width  <= 1.0f) dec.width  * 1280f else dec.width
        val baseH = if (dec.height <= 1.0f) dec.height * 720f  else dec.height
        val finalW = (baseW * scaleX).toInt().coerceAtLeast(1)
        val finalH = (baseH * scaleY).toInt().coerceAtLeast(1)

        // 화면 픽셀 위치 (0-1 → 0-1280/720)
        val screenX = cx * 1280f + shakeX
        val screenY = cy * 720f  + shakeY

        val old      = g.composite
        val oldHint  = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        val oldTx    = g.transform

        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity.coerceIn(0f, 1f))

        // pivot 기준 AffineTransform 구성
        val pivX = dec.pivotX * finalW
        val pivY = dec.pivotY * finalH
        val at   = AffineTransform()
        at.translate((screenX - pivX).toDouble(), (screenY - pivY).toDouble())
        if (rotation != 0f) at.rotate(Math.toRadians(rotation.toDouble()), pivX.toDouble(), pivY.toDouble())

        // 이미지 → finalW×finalH 로 스케일
        val imgScaleX = finalW.toDouble() / img.width
        val imgScaleY = finalH.toDouble() / img.height
        at.scale(imgScaleX, imgScaleY)

        g.drawImage(img, at, null)

        // 틴트 오버레이
        if (tintA > 0) {
            val tintTx = AffineTransform()
            tintTx.translate((screenX - pivX).toDouble(), (screenY - pivY).toDouble())
            if (rotation != 0f) tintTx.rotate(Math.toRadians(rotation.toDouble()), pivX.toDouble(), pivY.toDouble())
            g.transform = tintTx
            g.color = Color(tintR, tintG, tintB, tintA.coerceIn(0, 255))
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (tintA / 255f).coerceIn(0f, 1f))
            g.fillRect(0, 0, finalW, finalH)
        }

        g.transform = oldTx
        g.composite = old
        if (oldHint != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldHint)
        else g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    }

    // ── 화면 전체 효과 ────────────────────────────────────────────────────────
    private fun renderScreenEffect(g: Graphics2D, eff: ScreenEffect, elapsedMs: Long) {
        val rawT = if (eff.durationMs <= 0L) 1f else
            (elapsedMs.toFloat() / eff.durationMs).coerceIn(0f, 1f)
        val t = ease(rawT, eff.easing)

        when (eff.type) {
            "flash" -> {
                // 중간(t=0.5)에서 최대 밝기, 양 끝에서 투명
                val peakT = if (rawT < 0.5f) rawT * 2f else (1f - rawT) * 2f
                val alpha = (eff.a * peakT).toInt().coerceIn(0, 255)
                if (alpha <= 0) return
                val old = g.composite
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 255f)
                g.color = Color(eff.r, eff.g, eff.b)
                g.fillRect(0, 0, 1280, 720)
                g.composite = old
            }
            "colorOverlay" -> {
                val alpha = (eff.a * (1f - t)).toInt().coerceIn(0, 255)
                if (alpha <= 0) return
                val old = g.composite
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 255f)
                g.color = Color(eff.r, eff.g, eff.b)
                g.fillRect(0, 0, 1280, 720)
                g.composite = old
            }
            "vignette" -> {
                val strength = (eff.intensity * (1f - t)).coerceIn(0f, 1f)
                if (strength <= 0f) return
                val darkAlpha = (strength * 220).toInt().coerceIn(0, 220)
                val paint = RadialGradientPaint(
                    640f, 360f,
                    700f,
                    floatArrayOf(0.4f, 1.0f),
                    arrayOf(Color(0, 0, 0, 0), Color(0, 0, 0, darkAlpha))
                )
                val old = g.paint
                val oldComp = g.composite
                g.paint     = paint
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER)
                g.fillRect(0, 0, 1280, 720)
                g.paint     = old
                g.composite = oldComp
            }
        }
    }
}
