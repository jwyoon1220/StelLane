package io.github.jwyoon1220.engine.vulkan

import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.DrawFont
import io.github.jwyoon1220.engine.DrawFontMetrics
import io.github.jwyoon1220.engine.render.RenderColor
import org.slf4j.LoggerFactory
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Rectangle
import java.awt.Stroke
import java.awt.image.BufferedImage

/**
 * [DrawContext]의 Vulkan 구현 — [Vulkan2DBatcher] 위에 사각형/둥근사각형/타원/선/다각형/텍스트를 그립니다.
 * [io.github.jwyoon1220.engine.vulkan.VulkanBackend]가 배처 하나당 인스턴스 하나를 소유합니다.
 *
 * ## 구현 현황
 * - 완료: 사각형/둥근사각형/타원/원/선/다각형(채우기+외곽선), 변환/클립 스택, globalAlpha,
 *   SDF 텍스트(drawString 계열, measureStringWidth, FontMetrics, setFontBlur — SDF alpha 전환
 *   구간을 넓혀 근사), 선형/방사형/박스 그라디언트, BufferedImage 그리기(getOrUploadImage/
 *   drawImage/invalidateImage).
 * - 미구현(경고 로그 후 무시): `drawNvgImage`/`drawNvgImageTransformed`(원시 정수 핸들 버전 —
 *   실사용은 비디오 배경뿐인데, 비디오 배경 자체는 [VulkanBackend]가 이 경로를 거치지 않고
 *   [io.github.jwyoon1220.engine.vulkan.VulkanVideoTexture]로 직접 그림 — 그 외 원시 핸들
 *   호출자는 없음).
 *
 * 사각형/도형은 전부 CPU에서 폴리곤으로 테셀레이션해 단색 모드(mode=SOLID)로 그립니다 — 프래그먼트
 * 셰이더에서 SDF로 도형을 계산하는 대신 이렇게 하면 사각형/둥근사각형/타원/텍스트/이미지가 전부
 * 같은 정점 포맷과 같은 파이프라인을 쓰게 되어 배칭이 끊기지 않습니다. 그라디언트는 정점마다
 * 다른 색을 넣어 GPU 래스터라이저의 보간을 그대로 활용합니다(선형은 4개 코너, 방사형/박스는
 * 격자로 잘게 나눠 비선형 falloff를 근사). 텍스트만 SDF 텍스처를 실제로 샘플링합니다
 * (mode=MODE_SDF_TEXT) — 폰트 크기를 자유롭게 바꿔도 흐려지지 않도록.
 */
class VulkanDrawContext(
    private val batcher: Vulkan2DBatcher,
    /** 단색 채우기/외곽선이 샘플링할 1x1 흰 텍스처의 디스크립터 셋 ([VulkanContext.whiteTextureDescriptorSet]). */
    private val whiteDescriptorSet: Long,
    private val fontSystem: VulkanFontSystem,
    private val imageCache: VulkanImageCache,
    override val width: Int,
    override val height: Int
) : DrawContext {
    private val log = LoggerFactory.getLogger(VulkanDrawContext::class.java)
    private val unsupportedWarned = HashSet<String>()
    private fun warnOnce(what: String) {
        if (unsupportedWarned.add(what)) {
            log.warn("[Vulkan] {}는 아직 구현되지 않았습니다 — 무시합니다", what)
        }
    }

    // ── 상태 ────────────────────────────────────────────────────────────────
    private var _color: Color? = null
    override var color: Color
        get() {
            var c = _color
            if (c == null) {
                c = Color(renderColor.r, renderColor.g, renderColor.b, renderColor.a)
                _color = c
            }
            return c
        }
        set(value) {
            _color = value
            _renderColor = RenderColor.fromAwt(value)
        }

    private var _renderColor: RenderColor = RenderColor.WHITE
    override var renderColor: RenderColor
        get() = _renderColor
        set(value) {
            _renderColor = value
            _color = null
        }

    override var font: DrawFont? = null
    override var globalAlpha: Float = 1f

    private var _strokeWidth: Float = 1f
    override var stroke: Stroke = BasicStroke(1f)
        set(value) {
            field = value
            _strokeWidth = (value as? BasicStroke)?.lineWidth ?: 1f
        }

    override val clipBounds: Rectangle get() = Rectangle(0, 0, width, height)

    // ── 색상 성분(현재 renderColor/_color + globalAlpha 반영) ─────────────────
    private fun fillR() = _color?.let { it.red / 255f }   ?: _renderColor.rf
    private fun fillG() = _color?.let { it.green / 255f } ?: _renderColor.gf
    private fun fillB() = _color?.let { it.blue / 255f }  ?: _renderColor.bf
    private fun fillA() = (_color?.let { it.alpha / 255f } ?: _renderColor.af) * globalAlpha

    // ── 프레임 생명주기 ─────────────────────────────────────────────────────
    // 배처 자체의 begin/end(변환·클립 스택 초기화 포함)는 VulkanContext가 호출합니다.
    // 여기서는 DrawContext 쪽 그리기 상태(색상/알파/폰트/스트로크)만 프레임 기본값으로 되돌립니다.
    override fun beginFrame(fbWidth: Int, fbHeight: Int, devicePixelRatio: Float) {
        _color = null; _renderColor = RenderColor.WHITE
        globalAlpha = 1f
        stroke = BasicStroke(1f)
        font = null
    }

    override fun endFrame() = Unit

    // ── 상태 저장/복원 ──────────────────────────────────────────────────────
    override fun save() = batcher.save()
    override fun restore() = batcher.restore()

    // ── 좌표 변환 ───────────────────────────────────────────────────────────
    override fun translate(tx: Double, ty: Double) = batcher.transform.translate(tx.toFloat(), ty.toFloat())
    override fun translate(tx: Float, ty: Float) = batcher.transform.translate(tx, ty)
    override fun translate(tx: Int, ty: Int) = batcher.transform.translate(tx.toFloat(), ty.toFloat())
    override fun scale(sx: Double, sy: Double) = batcher.transform.scale(sx.toFloat(), sy.toFloat())
    override fun scale(sx: Float, sy: Float) = batcher.transform.scale(sx, sy)
    override fun rotate(theta: Double) = batcher.transform.rotate(theta.toFloat())
    override fun rotate(theta: Float) = batcher.transform.rotate(theta)

    // ── 클립 / 시저 ─────────────────────────────────────────────────────────
    override fun setClip(x: Int, y: Int, w: Int, h: Int) = setClip(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
    override fun setClip(x: Float, y: Float, w: Float, h: Float) = batcher.setClip(x, y, w, h)
    override fun resetClip() = batcher.resetClip()

    // ── FontMetrics ─────────────────────────────────────────────────────────
    override val fontMetrics: DrawFontMetrics get() = getFontMetrics(font ?: io.github.jwyoon1220.engine.FontRegistry.regular)
    override fun getFontMetrics(f: DrawFont): DrawFontMetrics {
        val atlas = fontSystem.atlasFor(f.id)
        val s = f.size / VulkanFontAtlas.REFERENCE_PX
        return object : DrawFontMetrics {
            override fun stringWidth(str: String) = measureStringWidth(str, f).toInt()
            override val ascent  = ((atlas?.ascent ?: 0f) * s).toInt()
            override val descent = ((atlas?.descent ?: 0f) * -s).toInt() // NanoVG 쪽과 부호 규약 맞춤(양수)
            override val height  = (((atlas?.ascent ?: 0f) - (atlas?.descent ?: 0f) + (atlas?.lineGap ?: 0f)) * s).toInt()
        }
    }

    // ── 사각형 ──────────────────────────────────────────────────────────────
    override fun fillRect(x: Int, y: Int, w: Int, h: Int) = fillRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
    override fun fillRect(x: Float, y: Float, w: Float, h: Float) {
        batcher.bindTexture(whiteDescriptorSet)
        batcher.pushQuad(x, y, w, h, 0f, 0f, 1f, 1f, fillR(), fillG(), fillB(), fillA(), Vulkan2DVertex.MODE_SOLID)
    }
    override fun drawRect(x: Int, y: Int, w: Int, h: Int) = drawRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
    override fun drawRect(x: Float, y: Float, w: Float, h: Float) {
        strokeRectPath(floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h), 4)
    }

    // ── 둥근 사각형 ─────────────────────────────────────────────────────────
    override fun fillRoundRect(x: Int, y: Int, w: Int, h: Int, arcW: Int, arcH: Int) =
        fillRoundRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), minOf(arcW, arcH) / 2f)
    override fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        val pts = Vulkan2DGeometry.roundedRectPoints(x, y, w, h, r)
        batcher.bindTexture(whiteDescriptorSet)
        batcher.pushFan(x + w / 2f, y + h / 2f, pts, pts.size / 2, fillR(), fillG(), fillB(), fillA(), Vulkan2DVertex.MODE_SOLID)
    }
    override fun drawRoundRect(x: Int, y: Int, w: Int, h: Int, arcW: Int, arcH: Int) =
        drawRoundRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), minOf(arcW, arcH) / 2f)
    override fun drawRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        val pts = Vulkan2DGeometry.roundedRectPoints(x, y, w, h, r)
        strokeRectPath(pts, pts.size / 2)
    }

    private fun strokeRectPath(pts: FloatArray, count: Int) {
        batcher.bindTexture(whiteDescriptorSet)
        for (i in 0 until count) {
            val j = (i + 1) % count
            batcher.pushLineSegment(
                pts[i * 2], pts[i * 2 + 1], pts[j * 2], pts[j * 2 + 1], _strokeWidth,
                fillR(), fillG(), fillB(), fillA(), Vulkan2DVertex.MODE_SOLID
            )
        }
    }

    // ── 선 ──────────────────────────────────────────────────────────────────
    override fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int) =
        drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        batcher.bindTexture(whiteDescriptorSet)
        batcher.pushLineSegment(x1, y1, x2, y2, _strokeWidth, fillR(), fillG(), fillB(), fillA(), Vulkan2DVertex.MODE_SOLID)
    }

    // ── 타원 ────────────────────────────────────────────────────────────────
    override fun fillOval(x: Int, y: Int, w: Int, h: Int) {
        val cx = x + w / 2f; val cy = y + h / 2f
        val pts = Vulkan2DGeometry.ellipsePoints(cx, cy, w / 2f, h / 2f)
        batcher.bindTexture(whiteDescriptorSet)
        batcher.pushFan(cx, cy, pts, pts.size / 2, fillR(), fillG(), fillB(), fillA(), Vulkan2DVertex.MODE_SOLID)
    }
    override fun drawOval(x: Int, y: Int, w: Int, h: Int) {
        val cx = x + w / 2f; val cy = y + h / 2f
        val pts = Vulkan2DGeometry.ellipsePoints(cx, cy, w / 2f, h / 2f)
        strokeRectPath(pts, pts.size / 2)
    }
    override fun fillCircle(cx: Float, cy: Float, r: Float) {
        val pts = Vulkan2DGeometry.ellipsePoints(cx, cy, r, r)
        batcher.bindTexture(whiteDescriptorSet)
        batcher.pushFan(cx, cy, pts, pts.size / 2, fillR(), fillG(), fillB(), fillA(), Vulkan2DVertex.MODE_SOLID)
    }

    // ── 다각형 ──────────────────────────────────────────────────────────────
    override fun fillPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) {
        if (nPoints < 3) return
        var cx = 0f; var cy = 0f
        val pts = FloatArray(nPoints * 2)
        for (i in 0 until nPoints) {
            pts[i * 2] = xPoints[i].toFloat(); pts[i * 2 + 1] = yPoints[i].toFloat()
            cx += xPoints[i]; cy += yPoints[i]
        }
        cx /= nPoints; cy /= nPoints
        // 볼록 다각형 전제(팬 삼각분할) — 실제 사용처(플레이헤드 삼각형 등)는 항상 볼록입니다.
        batcher.bindTexture(whiteDescriptorSet)
        batcher.pushFan(cx, cy, pts, nPoints, fillR(), fillG(), fillB(), fillA(), Vulkan2DVertex.MODE_SOLID)
    }
    override fun drawPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) {
        if (nPoints < 2) return
        val pts = FloatArray(nPoints * 2)
        for (i in 0 until nPoints) { pts[i * 2] = xPoints[i].toFloat(); pts[i * 2 + 1] = yPoints[i].toFloat() }
        strokeRectPath(pts, nPoints)
    }

    // ── 텍스트 (SDF 아틀라스) ──────────────────────────────────────────────────
    // NanoVG 처럼 폰트 크기를 자유롭게 바꿔도 아틀라스를 다시 굽지 않습니다 — 참조 크기(48px)로
    // 구운 SDF 비트맵을 쿼드 크기만 스케일해서 그립니다.

    override fun drawString(str: String, x: Int, y: Int) = drawString(str, x.toFloat(), y.toFloat())
    override fun drawString(str: String, x: Float, y: Float) = drawAligned(str, x, y, Align.LEFT)
    override fun drawStringCentered(str: String, cx: Float, y: Float) = drawAligned(str, cx, y, Align.CENTER)
    override fun drawStringRight(str: String, x: Float, y: Float) = drawAligned(str, x, y, Align.RIGHT)
    override fun drawStringLeft(str: String, x: Float, y: Float) = drawString(str, x, y)

    /** SDF 거리값의 alpha 전환 구간을 넓혀 NanoVG의 가우시안 fontBlur를 근사합니다 ([Vulkan2DBatcher.setFontBlur] 참고). */
    override fun setFontBlur(blur: Float) = batcher.setFontBlur(blur)

    override fun measureStringWidth(str: String, f: DrawFont): Float {
        if (str.isEmpty()) return 0f
        val atlas = fontSystem.atlasFor(f.id) ?: return 0f
        val s = f.size / VulkanFontAtlas.REFERENCE_PX
        var w = 0f
        var prevCp = -1
        forEachCodepoint(str) { cp ->
            if (prevCp >= 0) w += atlas.kernAdvancePx(prevCp, cp) * s
            w += (atlas.getOrBake(cp)?.advance ?: 0f) * s
            prevCp = cp
        }
        return w
    }
    override fun measureStringWidth(str: String): Float = font?.let { measureStringWidth(str, it) } ?: 0f

    private enum class Align { LEFT, CENTER, RIGHT }

    private fun drawAligned(str: String, anchorX: Float, y: Float, align: Align) {
        val f = font ?: return
        if (str.isEmpty()) return
        val atlas = fontSystem.atlasFor(f.id) ?: return
        val s = f.size / VulkanFontAtlas.REFERENCE_PX

        val startX = when (align) {
            Align.LEFT   -> anchorX
            Align.CENTER -> anchorX - measureStringWidth(str, f) / 2f
            Align.RIGHT  -> anchorX - measureStringWidth(str, f)
        }

        batcher.bindTexture(atlas.descriptorSet)
        var cursorX = startX
        var prevCp = -1
        val r = fillR(); val g = fillG(); val b = fillB(); val a = fillA()
        forEachCodepoint(str) { cp ->
            if (prevCp >= 0) cursorX += atlas.kernAdvancePx(prevCp, cp) * s
            val glyph = atlas.getOrBake(cp)
            if (glyph != null) {
                if (glyph.hasBitmap) {
                    val qx = cursorX + glyph.xoff * s
                    val qy = y + glyph.yoff * s
                    val qw = glyph.width * s
                    val qh = glyph.height * s
                    batcher.pushQuad(qx, qy, qw, qh, glyph.u0, glyph.v0, glyph.u1, glyph.v1, r, g, b, a, Vulkan2DVertex.MODE_SDF_TEXT)
                }
                cursorX += glyph.advance * s
            }
            prevCp = cp
        }
    }

    /** UTF-16 서로게이트 쌍을 올바르게 처리하는 코드포인트 순회(한글 완성형은 BMP 안이라 대부분 해당 없음). */
    private inline fun forEachCodepoint(str: String, action: (Int) -> Unit) {
        var i = 0
        while (i < str.length) {
            val cp = str.codePointAt(i)
            action(cp)
            i += Character.charCount(cp)
        }
    }

    // ── 이미지 ──────────────────────────────────────────────────────────────
    override fun drawImage(img: java.awt.Image?, x: Int, y: Int, w: Int, h: Int, observer: java.awt.image.ImageObserver?) {
        if (img !is BufferedImage) return
        val iw = if (w < 0) img.width else w
        val ih = if (h < 0) img.height else h
        drawImage(img, x.toFloat(), y.toFloat(), iw.toFloat(), ih.toFloat())
    }
    override fun drawImage(img: java.awt.Image?, x: Float, y: Float, w: Float, h: Float) {
        if (img !is BufferedImage) return
        val handle = imageCache.getOrUpload(img)
        val descSet = imageCache.descriptorSetFor(handle) ?: return
        batcher.bindTexture(descSet)
        // 이미지 자체는 색조 없이 그대로(흰색×알파) — NvgDrawContext.drawNvgImage와 동일한 동작.
        batcher.pushQuad(x, y, w, h, 0f, 0f, 1f, 1f, 1f, 1f, 1f, globalAlpha, Vulkan2DVertex.MODE_IMAGE)
    }

    /**
     * 원시 정수 이미지 핸들 버전 — 실사용은 비디오 배경뿐인데(NanoVGBackend가
     * `videoBackground.getNvgImageHandle(vg)`로 얻은 NanoVG 전용 핸들을 넘김) VideoBackground가
     * 아직 GL 텍스처 전용이라 Vulkan에서 그릴 유효한 핸들 자체가 없습니다 — 별도 known-gap.
     */
    override fun drawNvgImage(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float) = warnOnce("drawNvgImage(비디오 배경 등 원시 핸들)")
    override fun drawNvgImageTransformed(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float) = warnOnce("drawNvgImageTransformed(비디오 배경 등 원시 핸들)")
    override fun getOrUploadImage(img: BufferedImage): Int = imageCache.getOrUpload(img)
    override fun invalidateImage(img: BufferedImage) = imageCache.invalidate(img)

    // ── 그라디언트 ──────────────────────────────────────────────────────────
    // 전부 "정점마다 다른 색"을 GPU 래스터라이저가 보간하게 만드는 방식 — 셰이더는 그대로 mode=SOLID.
    // 선형은 축에 대한 사영 t로 정확히 재현되고(격자 1칸이면 이미 정확한 평면 보간), 방사형/박스는
    // 비선형 falloff라 격자를 잘게 나눠 근사합니다.

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun linearColorAt(
        px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float,
        sr: Float, sg: Float, sb: Float, sa: Float, er: Float, eg: Float, eb: Float, ea: Float
    ): FloatArray {
        val dx = x1 - x0; val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-6f) 0f else (((px - x0) * dx + (py - y0) * dy) / len2).coerceIn(0f, 1f)
        return floatArrayOf(lerp(sr, er, t), lerp(sg, eg, t), lerp(sb, eb, t), lerp(sa, ea, t) * globalAlpha)
    }

    private fun radialColorAt(
        px: Float, py: Float, cx: Float, cy: Float, inR: Float, outR: Float,
        ir: Float, ig: Float, ib: Float, ia: Float, or_: Float, og: Float, ob: Float, oa: Float
    ): FloatArray {
        val d = kotlin.math.hypot((px - cx).toDouble(), (py - cy).toDouble()).toFloat()
        val range = (outR - inR).coerceAtLeast(1e-3f)
        val t = ((d - inR) / range).coerceIn(0f, 1f)
        return floatArrayOf(lerp(ir, or_, t), lerp(ig, og, t), lerp(ib, ob, t), lerp(ia, oa, t) * globalAlpha)
    }

    /** Inigo Quilez의 둥근 사각형 SDF — NanoVG의 nvgBoxGradient 내부 구현과 같은 공식. */
    private fun sdRoundRect(px: Float, py: Float, cx: Float, cy: Float, halfW: Float, halfH: Float, r: Float): Float {
        val qx = kotlin.math.abs(px - cx) - halfW + r
        val qy = kotlin.math.abs(py - cy) - halfH + r
        val outsideX = qx.coerceAtLeast(0f); val outsideY = qy.coerceAtLeast(0f)
        return minOf(maxOf(qx, qy), 0f) + kotlin.math.hypot(outsideX.toDouble(), outsideY.toDouble()).toFloat() - r
    }

    private fun boxColorAt(
        px: Float, py: Float, cx: Float, cy: Float, halfW: Float, halfH: Float, r: Float, feather: Float,
        ir: Float, ig: Float, ib: Float, ia: Float, or_: Float, og: Float, ob: Float, oa: Float
    ): FloatArray {
        val d = sdRoundRect(px, py, cx, cy, halfW, halfH, r)
        val t = ((d + feather * 0.5f) / feather.coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
        return floatArrayOf(lerp(ir, or_, t), lerp(ig, og, t), lerp(ib, ob, t), lerp(ia, oa, t) * globalAlpha)
    }

    override fun fillRadialGradient(x: Float, y: Float, w: Float, h: Float, cx: Float, cy: Float, inR: Float, outR: Float, innerColor: Color, outerColor: Color) =
        fillRadialGradient(x, y, w, h, cx, cy, inR, outR, RenderColor.fromAwt(innerColor), RenderColor.fromAwt(outerColor))
    override fun fillRadialGradient(x: Float, y: Float, w: Float, h: Float, cx: Float, cy: Float, inR: Float, outR: Float, innerColor: RenderColor, outerColor: RenderColor) {
        batcher.bindTexture(whiteDescriptorSet)
        batcher.pushGridGradient(x, y, w, h, 12, Vulkan2DVertex.MODE_SOLID) { px, py ->
            radialColorAt(px, py, cx, cy, inR, outR, innerColor.rf, innerColor.gf, innerColor.bf, innerColor.af, outerColor.rf, outerColor.gf, outerColor.bf, outerColor.af)
        }
    }

    override fun fillLinearGradient(x: Float, y: Float, w: Float, h: Float, x0: Float, y0: Float, x1: Float, y1: Float, startColor: Color, endColor: Color) =
        fillLinearGradient(x, y, w, h, x0, y0, x1, y1, RenderColor.fromAwt(startColor), RenderColor.fromAwt(endColor))
    override fun fillLinearGradient(x: Float, y: Float, w: Float, h: Float, x0: Float, y0: Float, x1: Float, y1: Float, startColor: RenderColor, endColor: RenderColor) {
        batcher.bindTexture(whiteDescriptorSet)
        // 선형 그라디언트는 평면 함수라 1칸 격자(=4개 코너)만으로 이미 수학적으로 정확합니다.
        batcher.pushGridGradient(x, y, w, h, 1, Vulkan2DVertex.MODE_SOLID) { px, py ->
            linearColorAt(px, py, x0, y0, x1, y1, startColor.rf, startColor.gf, startColor.bf, startColor.af, endColor.rf, endColor.gf, endColor.bf, endColor.af)
        }
    }

    override fun fillLinearGradientRoundRectTop(x: Float, y: Float, w: Float, h: Float, r: Float, x0: Float, y0: Float, x1: Float, y1: Float, startColor: RenderColor, endColor: RenderColor) {
        val pts = Vulkan2DGeometry.roundedRectPoints(x, y, w, h, r, r, 0f, 0f) // 위쪽만 둥글고 아래는 각짐
        batcher.bindTexture(whiteDescriptorSet)
        batcher.pushFanGradient(x + w / 2f, y + h / 2f, pts, pts.size / 2, Vulkan2DVertex.MODE_SOLID) { px, py ->
            linearColorAt(px, py, x0, y0, x1, y1, startColor.rf, startColor.gf, startColor.bf, startColor.af, endColor.rf, endColor.gf, endColor.bf, endColor.af)
        }
    }
    override fun fillLinearGradientRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float, x0: Float, y0: Float, x1: Float, y1: Float, startColor: RenderColor, endColor: RenderColor) {
        val pts = Vulkan2DGeometry.roundedRectPoints(x, y, w, h, r)
        batcher.bindTexture(whiteDescriptorSet)
        batcher.pushFanGradient(x + w / 2f, y + h / 2f, pts, pts.size / 2, Vulkan2DVertex.MODE_SOLID) { px, py ->
            linearColorAt(px, py, x0, y0, x1, y1, startColor.rf, startColor.gf, startColor.bf, startColor.af, endColor.rf, endColor.gf, endColor.bf, endColor.af)
        }
    }

    override fun fillBoxGradientRect(x: Float, y: Float, w: Float, h: Float, r: Float, feather: Float, innerColor: Color, outerColor: Color) =
        fillBoxGradientRect(x, y, w, h, r, feather, RenderColor.fromAwt(innerColor), RenderColor.fromAwt(outerColor))
    override fun fillBoxGradientRect(x: Float, y: Float, w: Float, h: Float, r: Float, feather: Float, innerColor: RenderColor, outerColor: RenderColor) {
        // NvgDrawContext와 동일하게 (x-feather, y-feather, w+feather*2, h+feather*2) 영역을 채웁니다.
        val fx = x - feather; val fy = y - feather; val fw = w + feather * 2; val fh = h + feather * 2
        val cx = x + w / 2f; val cy = y + h / 2f
        batcher.bindTexture(whiteDescriptorSet)
        batcher.pushGridGradient(fx, fy, fw, fh, 12, Vulkan2DVertex.MODE_SOLID) { px, py ->
            boxColorAt(px, py, cx, cy, w / 2f, h / 2f, r, feather, innerColor.rf, innerColor.gf, innerColor.bf, innerColor.af, outerColor.rf, outerColor.gf, outerColor.bf, outerColor.af)
        }
    }
}
