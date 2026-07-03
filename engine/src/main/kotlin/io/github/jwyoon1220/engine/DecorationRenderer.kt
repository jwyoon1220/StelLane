package io.github.jwyoon1220.engine

import io.github.jwyoon1220.core.data.DecEffect
import io.github.jwyoon1220.core.data.Decoration
import io.github.jwyoon1220.core.data.DecorationData
import io.github.jwyoon1220.core.data.ScreenEffect
import io.github.jwyoon1220.engine.AnimUtil
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.GlScreenEffectData
import io.github.jwyoon1220.engine.render.RenderColor
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
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
        private val GL_EFFECT_TYPES = setOf(
            "grayscale", "fade", "blur", "shader",
            "crt", "bloom", "pixelate", "chromab", "sepia", "invert",
            "vignette_gl", "filmgrain", "glitch"
        )
    }

    /** 이미지 캐시 (DrawContext가 GPU 업로드 담당). */
    private val imageCache = HashMap<String, BufferedImage?>()

    /** Sparkle 이펙트용 공유 Random — 매 프레임 객체 생성 방지. */
    private val rng = Random()

    /**
     * 초기화 시 depth 기준으로 정렬 & 분리 저장.
     * render()에서 매 프레임 filter+sortedBy+filter 3개 중간 리스트 생성을 제거합니다.
     */
    private val beforeNoteDecorations: List<Decoration> =
        data.decorations.filter { it.depth < 0 }.sortedBy { it.depth }
    private val afterNoteDecorations: List<Decoration> =
        data.decorations.filter { it.depth >= 0 }.sortedBy { it.depth }

    private fun image(path: String): BufferedImage? =
        imageCache.getOrPut(path) {
            runCatching { ImageIO.read(File(songDir, path)) }.getOrNull()
        }

    // ── 메인 렌더 진입점 ──────────────────────────────────────────────────────
    /** 노트 렌더링 이전(depth<0)과 이후(depth≥0) 두 번 호출한다. */
    fun render(g: DrawContext, currentMs: Long, beforeNotes: Boolean) {
        val list = if (beforeNotes) beforeNoteDecorations else afterNoteDecorations
        for (dec in list) {
            if (currentMs < dec.timeMs || currentMs >= dec.timeMs + dec.durationMs) continue
            renderDecoration(g, dec, currentMs - dec.timeMs)
        }
    }

    /**
     * 현재 활성화된 GL 후처리 효과 목록을 반환합니다.
     * [Renderer]가 FBO 캡처 전에 호출해 후처리 효과를 적용합니다.
     */
    fun collectGlEffects(currentMs: Long): List<GlScreenEffectData> {
        // 효과가 없는 프레임에서 리스트 할당 방지 — 실제 활성 효과가 있을 때만 생성
        var result: MutableList<GlScreenEffectData>? = null
        for (eff in data.screenEffects) {
            if (eff.type !in GL_EFFECT_TYPES) continue
            if (currentMs < eff.timeMs || currentMs >= eff.timeMs + eff.durationMs) continue
            val elapsed = currentMs - eff.timeMs
            val rawT = if (eff.durationMs <= 0L) 1f else (elapsed.toFloat() / eff.durationMs).coerceIn(0f, 1f)
            val t = AnimUtil.ease(rawT, eff.easing)
            val intensity = AnimUtil.lerp(eff.fromIntensity, eff.toIntensity, t)
            val shaderFile = if (eff.type == "shader" && eff.shaderFile.isNotEmpty()) File(songDir, eff.shaderFile) else null
            if (result == null) result = mutableListOf()
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
        return result ?: emptyList()
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
            val t = AnimUtil.ease(rawT, eff.easing)
            target = AnimUtil.lerp(eff.fromVolume.toFloat(), eff.toVolume.toFloat(), t).toInt().coerceIn(0, 100)
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
            if (elapsedMs < effStart) continue

            val rawT = if (eff.durationMs <= 0L) 1f else
                ((elapsedMs - effStart).toFloat() / eff.durationMs).coerceIn(0f, 1f)
            val t = AnimUtil.ease(rawT, eff.easing)

            when (eff.type) {
                "fadeIn"      -> opacity   = AnimUtil.lerp(0f, dec.opacity, t)
                "fadeOut"     -> opacity   = AnimUtil.lerp(dec.opacity, 0f, t)
                "opacityTo"   -> eff.toOpacity?.let  { opacity   = AnimUtil.lerp(dec.opacity,   it, t) }
                "moveTo"      -> {
                    eff.toX?.let { cx = AnimUtil.lerp(dec.x, it, t) }
                    eff.toY?.let { cy = AnimUtil.lerp(dec.y, it, t) }
                }
                "rotateTo"    -> eff.toRotation?.let { rotation  = AnimUtil.lerp(dec.rotation,  it, t) }
                "scaleTo"     -> {
                    eff.toScaleX?.let { scaleX = AnimUtil.lerp(dec.scaleX, it, t) }
                    eff.toScaleY?.let { scaleY = AnimUtil.lerp(dec.scaleY, it, t) }
                }
                "colorFilter" -> { tintR = eff.r; tintG = eff.g; tintB = eff.b; tintA = eff.a }
                "shake"       -> {
                    val phase = elapsedMs * eff.frequency / 1000.0 * 2.0 * PI
                    shakeX = (sin(phase) * eff.amplitude).toFloat()
                    shakeY = (sin(phase * 1.3 + 1.0) * eff.amplitude * 0.6f).toFloat()
                }
                "sparkle"     -> sparkleIntensity = eff.amplitude
            }
        }

        if (opacity <= 0f) return
        if (img == null && sparkleIntensity <= 0f) return

        val baseW = if (dec.width  <= 1.0f) dec.width  * 1280f else dec.width
        val baseH = if (dec.height <= 1.0f) dec.height * 720f  else dec.height
        val finalW = (baseW * scaleX).toInt().coerceAtLeast(1)
        val finalH = (baseH * scaleY).toInt().coerceAtLeast(1)

        val screenX = cx * 1280f + shakeX
        val screenY = cy * 720f  + shakeY

        val pivX = dec.pivotX * finalW
        val pivY = dec.pivotY * finalH

        val oldAlpha = g.globalAlpha
        g.globalAlpha = opacity.coerceIn(0f, 1f)

        if (img != null) {
            g.save()
            g.translate(screenX.toDouble(), screenY.toDouble())
            if (rotation != 0f) g.rotate(Math.toRadians(rotation.toDouble()))
            g.translate(-pivX.toDouble(), -pivY.toDouble())
            g.drawImage(img, 0, 0, finalW, finalH, null)
            g.restore()

            if (tintA > 0) {
                g.save()
                g.globalAlpha = (tintA / 255f).coerceIn(0f, 1f)
                g.translate(screenX.toDouble(), screenY.toDouble())
                if (rotation != 0f) g.rotate(Math.toRadians(rotation.toDouble()))
                g.translate(-pivX.toDouble(), -pivY.toDouble())
                g.renderColor = RenderColor.of(tintR, tintG, tintB)
                g.fillRect(0, 0, finalW, finalH)
                g.restore()
            }
        }

        if (sparkleIntensity > 0f) {
            g.save()
            g.translate(screenX.toDouble(), screenY.toDouble())
            if (rotation != 0f) g.rotate(Math.toRadians(rotation.toDouble()))
            g.translate(-pivX.toDouble(), -pivY.toDouble())
            g.globalAlpha = opacity.coerceIn(0f, 1f)
            g.renderColor = RenderColor.YELLOW
            rng.setSeed(dec.timeMs + elapsedMs / 100)
            val count = (sparkleIntensity * 10).toInt().coerceIn(1, 100)
            for (i in 0 until count) {
                val px = rng.nextInt(finalW.coerceAtLeast(1))
                val py = rng.nextInt(finalH.coerceAtLeast(1))
                val size = rng.nextInt(5) + 2
                g.fillOval(px - size / 2, py - size / 2, size, size)
            }
            g.restore()
        }

        g.globalAlpha = oldAlpha
    }

    // ── 화면 전체 효과 ────────────────────────────────────────────────────────
    private fun renderScreenEffect(g: DrawContext, eff: ScreenEffect, elapsedMs: Long) {
        val rawT = if (eff.durationMs <= 0L) 1f else
            (elapsedMs.toFloat() / eff.durationMs).coerceIn(0f, 1f)
        val t = AnimUtil.ease(rawT, eff.easing)

        when (eff.type) {
            "flash" -> {
                val peakT = if (rawT < 0.5f) rawT * 2f else (1f - rawT) * 2f
                val alpha = (eff.a * peakT / 255f).coerceIn(0f, 1f)
                if (alpha <= 0f) return
                val old = g.globalAlpha
                g.globalAlpha = alpha
                g.renderColor = RenderColor.of(eff.r, eff.g, eff.b)
                g.fillRect(0, 0, 1280, 720)
                g.globalAlpha = old
            }
            "colorOverlay" -> {
                val alpha = (eff.a * (1f - t) / 255f).coerceIn(0f, 1f)
                if (alpha <= 0f) return
                val old = g.globalAlpha
                g.globalAlpha = alpha
                g.renderColor = RenderColor.of(eff.r, eff.g, eff.b)
                g.fillRect(0, 0, 1280, 720)
                g.globalAlpha = old
            }
            "vignette" -> {
                val strength = (eff.intensity * (1f - t)).coerceIn(0f, 1f)
                if (strength <= 0f) return
                val darkAlpha = (strength * 220).toInt().coerceIn(0, 220)
                val old = g.globalAlpha
                g.globalAlpha = 1f
                g.fillRadialGradient(0f, 0f, 1280f, 720f, 640f, 360f, 280f, 700f,
                    RenderColor.of(0, 0, 0, 0), RenderColor.of(0, 0, 0, darkAlpha))
                g.globalAlpha = old
            }
        }
    }
}
