package io.github.jwyoon1220.app

import io.github.jwyoon1220.core.data.DecEffect
import io.github.jwyoon1220.core.data.Decoration
import io.github.jwyoon1220.core.data.DecorationData
import io.github.jwyoon1220.core.data.ScreenEffect
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.GlScreenEffectData
import io.github.jwyoon1220.engine.render.RenderColor
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin

/**
 * decoration.json 에 정의된 장식/화면 효과를 매 프레임 렌더링한다.
 *
 * 좌표계 전제: DrawContext 는 1280×720 논리 공간.
 */
class DecorationRenderer(
    private val data: DecorationData,
    private val songDir: File
) {
    companion object {
        private val GL_EFFECT_TYPES = setOf("grayscale", "fade", "blur", "shader")
    }
    /** 이미지 캐시 (DrawContext가 GPU 업로드 담당). */
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
    fun render(g: DrawContext, currentMs: Long, beforeNotes: Boolean) {
        val active = data.decorations
            .filter { currentMs >= it.timeMs && currentMs < it.timeMs + it.durationMs }
            .sortedBy { it.depth }
            .filter { if (beforeNotes) it.depth < 0 else it.depth >= 0 }

        for (dec in active) {
            renderDecoration(g, dec, currentMs - dec.timeMs)
        }
    }

    /**
     * 현재 활성화된 GL 후처리 효과 목록을 반환합니다.
     * [Renderer]가 FBO 캡처 전에 호출해 후처리 효과를 적용합니다.
     */
    fun collectGlEffects(currentMs: Long): List<GlScreenEffectData> {
        val result = mutableListOf<GlScreenEffectData>()
        for (eff in data.screenEffects) {
            if (eff.type !in GL_EFFECT_TYPES) continue
            if (currentMs < eff.timeMs || currentMs >= eff.timeMs + eff.durationMs) continue
            val elapsed = currentMs - eff.timeMs
            val rawT = if (eff.durationMs <= 0L) 1f else (elapsed.toFloat() / eff.durationMs).coerceIn(0f, 1f)
            val t = ease(rawT, eff.easing)
            val intensity = lerp(eff.fromIntensity, eff.toIntensity, t)
            val shaderFile = if (eff.type == "shader" && eff.shaderFile.isNotEmpty()) File(songDir, eff.shaderFile) else null
            result.add(GlScreenEffectData(
                type       = eff.type,
                intensity  = intensity,
                r          = eff.r / 255f,
                g          = eff.g / 255f,
                b          = eff.b / 255f,
                a          = eff.a / 255f,
                shaderFile = shaderFile
            ))
        }
        return result
    }

    /**
     * audioFade 효과가 활성화된 경우 현재 목표 볼륨(0~100)을 반환합니다.
     * 활성 효과가 없으면 null 을 반환합니다.
     */
    fun computeTargetVolumePercent(currentMs: Long): Int? {
        var target: Int? = null
        for (eff in data.screenEffects) {
            if (eff.type != "audioFade") continue
            if (currentMs < eff.timeMs || currentMs >= eff.timeMs + eff.durationMs) continue
            val elapsed = currentMs - eff.timeMs
            val rawT = if (eff.durationMs <= 0L) 1f else (elapsed.toFloat() / eff.durationMs).coerceIn(0f, 1f)
            val t = ease(rawT, eff.easing)
            target = lerp(eff.fromVolume.toFloat(), eff.toVolume.toFloat(), t).toInt().coerceIn(0, 100)
        }
        return target
    }

    /** 화면 전체 효과 (flash, vignette 등) — HUD 렌더링 전에 호출. */
    fun renderScreenEffects(g: DrawContext, currentMs: Long) {
        for (eff in data.screenEffects) {
            if (currentMs < eff.timeMs || currentMs >= eff.timeMs + eff.durationMs) continue
            val elapsed = currentMs - eff.timeMs
            renderScreenEffect(g, eff, elapsed)
        }
    }

    // ── 개별 장식 렌더 ────────────────────────────────────────────────────────
    private fun renderDecoration(g: DrawContext, dec: Decoration, elapsedMs: Long) {
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
        var sparkleIntensity = 0f

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
                "glitch"      -> { /* simplified: skip glitch visual */ }
                "sparkle"     -> sparkleIntensity = eff.amplitude
                "grayscale"   -> { /* simplified: skip grayscale */ }
            }
        }

        if (opacity <= 0f) return
        if (img == null && sparkleIntensity <= 0f) return

        // 0~1 정규화 → 논리 픽셀 변환 (>1 은 레거시 절대 픽셀로 취급)
        val baseW = if (dec.width  <= 1.0f) dec.width  * 1280f else dec.width
        val baseH = if (dec.height <= 1.0f) dec.height * 720f  else dec.height
        val finalW = (baseW * scaleX).toInt().coerceAtLeast(1)
        val finalH = (baseH * scaleY).toInt().coerceAtLeast(1)

        // 화면 픽셀 위치 (0-1 → 0-1280/720)
        val screenX = cx * 1280f + shakeX
        val screenY = cy * 720f  + shakeY

        val pivX = dec.pivotX * finalW
        val pivY = dec.pivotY * finalH

        val oldComposite = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity.coerceIn(0f, 1f))

        // 이미지 렌더링 (NanoVG transform으로 회전/이동)
        if (img != null) {
            g.save()
            g.translate(screenX.toDouble(), screenY.toDouble())
            if (rotation != 0f) g.rotate(Math.toRadians(rotation.toDouble()))
            g.translate(-pivX.toDouble(), -pivY.toDouble())
            g.drawImage(img, 0, 0, finalW, finalH, null)
            g.restore()

            // 틴트 오버레이
            if (tintA > 0) {
                g.save()
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (tintA / 255f).coerceIn(0f, 1f))
                g.translate(screenX.toDouble(), screenY.toDouble())
                if (rotation != 0f) g.rotate(Math.toRadians(rotation.toDouble()))
                g.translate(-pivX.toDouble(), -pivY.toDouble())
                g.renderColor = RenderColor.of(tintR, tintG, tintB)
                g.fillRect(0, 0, finalW, finalH)
                g.restore()
            }
        }

        // 반짝임 입자 (Sparkle)
        if (sparkleIntensity > 0f) {
            g.save()
            g.translate(screenX.toDouble(), screenY.toDouble())
            if (rotation != 0f) g.rotate(Math.toRadians(rotation.toDouble()))
            g.translate(-pivX.toDouble(), -pivY.toDouble())
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity.coerceIn(0f, 1f))
            g.renderColor = RenderColor.YELLOW
            val random = java.util.Random(dec.timeMs + elapsedMs / 100)
            val count = (sparkleIntensity * 10).toInt().coerceIn(1, 100)
            for (i in 0 until count) {
                val px = random.nextInt(finalW.coerceAtLeast(1))
                val py = random.nextInt(finalH.coerceAtLeast(1))
                val size = random.nextInt(5) + 2
                g.fillOval(px - size/2, py - size/2, size, size)
            }
            g.restore()
        }

        g.composite = oldComposite
    }

    // ── 화면 전체 효과 ────────────────────────────────────────────────────────
    private fun renderScreenEffect(g: DrawContext, eff: ScreenEffect, elapsedMs: Long) {
        val rawT = if (eff.durationMs <= 0L) 1f else
            (elapsedMs.toFloat() / eff.durationMs).coerceIn(0f, 1f)
        val t = ease(rawT, eff.easing)

        when (eff.type) {
            "flash" -> {
                // 중간(t=0.5)에서 최대 밝기, 양 끝에서 투명
                val peakT = if (rawT < 0.5f) rawT * 2f else (1f - rawT) * 2f
                val alpha = (eff.a * peakT / 255f).coerceIn(0f, 1f)
                if (alpha <= 0f) return
                val old = g.composite
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                g.renderColor = RenderColor.of(eff.r, eff.g, eff.b)
                g.fillRect(0, 0, 1280, 720)
                g.composite = old
            }
            "colorOverlay" -> {
                val alpha = (eff.a * (1f - t) / 255f).coerceIn(0f, 1f)
                if (alpha <= 0f) return
                val old = g.composite
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                g.renderColor = RenderColor.of(eff.r, eff.g, eff.b)
                g.fillRect(0, 0, 1280, 720)
                g.composite = old
            }
            "vignette" -> {
                val strength = (eff.intensity * (1f - t)).coerceIn(0f, 1f)
                if (strength <= 0f) return
                val darkAlpha = (strength * 220).toInt().coerceIn(0, 220)
                val old = g.composite
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f)
                g.fillRadialGradient(0f, 0f, 1280f, 720f, 640f, 360f, 280f, 700f,
                    RenderColor.of(0, 0, 0, 0), RenderColor.of(0, 0, 0, darkAlpha))
                g.composite = old
            }
        }
    }
}
