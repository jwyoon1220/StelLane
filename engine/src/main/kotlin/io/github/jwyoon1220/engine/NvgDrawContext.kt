package io.github.jwyoon1220.engine

import org.lwjgl.nanovg.NVGColor
import org.lwjgl.nanovg.NVGPaint
import org.lwjgl.nanovg.NanoVG.*
import org.lwjgl.nanovg.NanoVGGL3.*
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Composite
import java.awt.Rectangle
import java.awt.Stroke
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import org.lwjgl.system.MemoryUtil
import io.github.jwyoon1220.engine.render.RenderColor
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap

/**
 * [DrawContext]의 NanoVG + OpenGL 기반 구현. [io.github.jwyoon1220.engine.render.NanoVGBackend]가 소유합니다.
 *
 * 메인 스레드(GLFW 루프)에서만 사용하세요.
 * 프레임 시작 시 [beginFrame], 종료 시 [endFrame] 을 호출해야 합니다.
 */
class NvgDrawContext(
    val vg: Long,
    override val width: Int,
    override val height: Int
) : DrawContext {
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

    private var _alpha: Float = 1f          // nvgGlobalAlpha 와 동기화
    private var _strokeWidth: Float = 1f    // nvgStrokeWidth 와 동기화

    /** 전역 알파 (0f~1f). nvgGlobalAlpha를 직접 설정합니다. */
    override var globalAlpha: Float
        get() = _alpha
        set(value) {
            _alpha = value.coerceIn(0f, 1f)
            nvgGlobalAlpha(vg, _alpha)
        }

    /** AlphaComposite.SRC_OVER 에서 알파를 추출해 전역 알파로 설정합니다 (호환용 — 인터페이스에는 없음). */
    var composite: Composite = AlphaComposite.SrcOver
        set(value) {
            field = value
            _alpha = when (value) {
                is AlphaComposite -> value.alpha
                else              -> 1f
            }
            nvgGlobalAlpha(vg, _alpha)
        }

    /** BasicStroke 에서 선 두께를 추출합니다. */
    override var stroke: Stroke = BasicStroke(1f)
        set(value) {
            field = value
            _strokeWidth = when (value) {
                is BasicStroke -> value.lineWidth
                else           -> 1f
            }
        }

    /** Graphics2D.getClipBounds() 호환 — 현재는 전체 프레임 영역을 반환합니다. */
    override val clipBounds: Rectangle get() = Rectangle(0, 0, width, height)

    // 클립 스택 (scissor)
    private data class ClipRect(val x: Float, val y: Float, val w: Float, val h: Float)
    private val clipStack = ArrayDeque<ClipRect?>()
    private var currentClip: ClipRect? = null

    // ── BufferedImage → NVG 이미지 캐시 ────────────────────────────────────
    // defaultReturnValue(-1): 없는 키 조회 시 0 대신 -1 반환 (0은 NVG 실패 코드와 구분 불가)
    private val imgCache = Int2IntOpenHashMap().also { it.defaultReturnValue(-1) }

    // ── NVGColor 재사용 버퍼 ───────────────────────────────────────────────
    private val nvgColor  = NVGColor.create()
    private val nvgColor2 = NVGColor.create()
    private val nvgPaint  = NVGPaint.create()

    // ── 프레임 생명주기 ─────────────────────────────────────────────────────
    override fun beginFrame(fbWidth: Int, fbHeight: Int, devicePixelRatio: Float) {
        nvgBeginFrame(vg, fbWidth.toFloat(), fbHeight.toFloat(), devicePixelRatio)
        _alpha = 1f
        nvgGlobalAlpha(vg, 1f)
        currentClip = null
        clipStack.clear()
    }

    override fun endFrame() {
        nvgEndFrame(vg)
    }

    // ── 상태 저장/복원 ──────────────────────────────────────────────────────
    override fun save() {
        nvgSave(vg)
        clipStack.addLast(currentClip)
    }

    override fun restore() {
        nvgRestore(vg)
        currentClip = clipStack.removeLastOrNull() ?: null
        _alpha = 1f  // nvgRestore 가 globalAlpha 도 복원하므로 내부 상태 동기화
    }

    // ── 좌표 변환 ───────────────────────────────────────────────────────────
    override fun translate(tx: Double, ty: Double) = nvgTranslate(vg, tx.toFloat(), ty.toFloat())
    override fun translate(tx: Float,  ty: Float ) = nvgTranslate(vg, tx, ty)
    override fun translate(tx: Int,    ty: Int   ) = nvgTranslate(vg, tx.toFloat(), ty.toFloat())
    override fun scale    (sx: Double, sy: Double) = nvgScale(vg, sx.toFloat(), sy.toFloat())
    override fun scale    (sx: Float,  sy: Float ) = nvgScale(vg, sx, sy)
    /** NanoVG 의 회전 단위는 라디안(radians)입니다. */
    override fun rotate   (theta: Double)          = nvgRotate(vg, theta.toFloat())
    override fun rotate   (theta: Float )          = nvgRotate(vg, theta)

    // ── 클립 / 시저 ─────────────────────────────────────────────────────────
    override fun setClip(x: Int, y: Int, w: Int, h: Int) = setClip(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
    override fun setClip(x: Float, y: Float, w: Float, h: Float) {
        nvgResetScissor(vg)
        nvgScissor(vg, x, y, w, h)
        currentClip = ClipRect(x, y, w, h)
    }
    override fun resetClip() {
        nvgResetScissor(vg)
        currentClip = null
    }

    // ── FontMetrics 호환 ────────────────────────────────────────────────────
    override val fontMetrics: DrawFontMetrics get() = getFontMetrics(font ?: FontRegistry.regular)

    override fun getFontMetrics(f: DrawFont): DrawFontMetrics = NvgFontMetrics(vg, f)

    // ── 내부: 색상 설정 ─────────────────────────────────────────────────────
    // _color == null → renderColor 경로 (float 직접 사용, /255 나눗셈 없음)
    // _color != null → awt.Color 경로 (마이그레이션 기간 호환)
    private fun applyFillColor() {
        val c = _color
        if (c == null) {
            nvgRGBAf(_renderColor.rf, _renderColor.gf, _renderColor.bf, _renderColor.af, nvgColor)
        } else {
            nvgRGBAf(c.red / 255f, c.green / 255f, c.blue / 255f, c.alpha / 255f, nvgColor)
        }
        nvgFillColor(vg, nvgColor)
    }

    private fun applyStrokeColor() {
        val c = _color
        if (c == null) {
            nvgRGBAf(_renderColor.rf, _renderColor.gf, _renderColor.bf, _renderColor.af, nvgColor)
        } else {
            nvgRGBAf(c.red / 255f, c.green / 255f, c.blue / 255f, c.alpha / 255f, nvgColor)
        }
        nvgStrokeColor(vg, nvgColor)
        nvgStrokeWidth(vg, _strokeWidth)
    }

    // ── 사각형 ──────────────────────────────────────────────────────────────
    override fun fillRect(x: Int, y: Int, w: Int, h: Int) {
        applyFillColor()
        nvgBeginPath(vg); nvgRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()); nvgFill(vg)
    }
    override fun fillRect(x: Float, y: Float, w: Float, h: Float) {
        applyFillColor()
        nvgBeginPath(vg); nvgRect(vg, x, y, w, h); nvgFill(vg)
    }
    override fun drawRect(x: Int, y: Int, w: Int, h: Int) {
        applyStrokeColor()
        nvgBeginPath(vg); nvgRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()); nvgStroke(vg)
    }
    override fun drawRect(x: Float, y: Float, w: Float, h: Float) {
        applyStrokeColor()
        nvgBeginPath(vg); nvgRect(vg, x, y, w, h); nvgStroke(vg)
    }

    // ── 둥근 사각형 ─────────────────────────────────────────────────────────
    override fun fillRoundRect(x: Int, y: Int, w: Int, h: Int, arcW: Int, arcH: Int) {
        applyFillColor()
        val r = minOf(arcW, arcH) / 2f
        nvgBeginPath(vg); nvgRoundedRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), r); nvgFill(vg)
    }
    override fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        applyFillColor()
        nvgBeginPath(vg); nvgRoundedRect(vg, x, y, w, h, r); nvgFill(vg)
    }
    override fun drawRoundRect(x: Int, y: Int, w: Int, h: Int, arcW: Int, arcH: Int) {
        applyStrokeColor()
        val r = minOf(arcW, arcH) / 2f
        nvgBeginPath(vg); nvgRoundedRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), r); nvgStroke(vg)
    }
    override fun drawRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        applyStrokeColor()
        nvgBeginPath(vg); nvgRoundedRect(vg, x, y, w, h, r); nvgStroke(vg)
    }

    // ── 선 ──────────────────────────────────────────────────────────────────
    override fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int) {
        applyStrokeColor()
        nvgBeginPath(vg)
        nvgMoveTo(vg, x1.toFloat(), y1.toFloat())
        nvgLineTo(vg, x2.toFloat(), y2.toFloat())
        nvgStroke(vg)
    }
    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        applyStrokeColor()
        nvgBeginPath(vg)
        nvgMoveTo(vg, x1, y1)
        nvgLineTo(vg, x2, y2)
        nvgStroke(vg)
    }

    // ── 타원 ────────────────────────────────────────────────────────────────
    override fun fillOval(x: Int, y: Int, w: Int, h: Int) {
        applyFillColor()
        val cx = x + w / 2f; val cy = y + h / 2f
        nvgBeginPath(vg); nvgEllipse(vg, cx, cy, w / 2f, h / 2f); nvgFill(vg)
    }
    override fun drawOval(x: Int, y: Int, w: Int, h: Int) {
        applyStrokeColor()
        val cx = x + w / 2f; val cy = y + h / 2f
        nvgBeginPath(vg); nvgEllipse(vg, cx, cy, w / 2f, h / 2f); nvgStroke(vg)
    }
    override fun fillCircle(cx: Float, cy: Float, r: Float) {
        applyFillColor()
        nvgBeginPath(vg); nvgCircle(vg, cx, cy, r); nvgFill(vg)
    }

    // ── 다각형 ──────────────────────────────────────────────────────────────
    override fun fillPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) {
        if (nPoints < 2) return
        applyFillColor()
        nvgBeginPath(vg)
        nvgMoveTo(vg, xPoints[0].toFloat(), yPoints[0].toFloat())
        for (i in 1 until nPoints) nvgLineTo(vg, xPoints[i].toFloat(), yPoints[i].toFloat())
        nvgClosePath(vg); nvgFill(vg)
    }
    override fun drawPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) {
        if (nPoints < 2) return
        applyStrokeColor()
        nvgBeginPath(vg)
        nvgMoveTo(vg, xPoints[0].toFloat(), yPoints[0].toFloat())
        for (i in 1 until nPoints) nvgLineTo(vg, xPoints[i].toFloat(), yPoints[i].toFloat())
        nvgClosePath(vg); nvgStroke(vg)
    }

    // ── 텍스트 ──────────────────────────────────────────────────────────────
    /** 텍스트를 (x, y) 에 그립니다. y 는 baseline 기준입니다 (Graphics2D 호환). */
    override fun drawString(str: String, x: Int, y: Int) = drawString(str, x.toFloat(), y.toFloat())
    override fun drawString(str: String, x: Float, y: Float) {
        val f = font ?: return
        nvgFontFaceId(vg, FontRegistry.nvgFontId(vg, f.id))
        nvgFontSize(vg, f.size)
        nvgTextAlign(vg, NVG_ALIGN_LEFT or NVG_ALIGN_BASELINE)
        applyFillColor()
        nvgText(vg, x, y, str)
    }

    // ── 이미지 그리기 ────────────────────────────────────────────────────────
    /**
     * BufferedImage 를 NVG 텍스처로 업로드하고 (x, y, w, h) 에 그립니다.
     * 최초 호출 시 업로드 비용이 발생합니다. 이미지 내용이 변경되지 않는 경우 캐시를 재사용합니다.
     */
    override fun drawImage(
        img: java.awt.Image?,
        x: Int, y: Int, w: Int, h: Int,
        observer: java.awt.image.ImageObserver?
    ) {
        if (img !is BufferedImage) return
        val iw = if (w < 0) img.width  else w
        val ih = if (h < 0) img.height else h
        val handle = getOrUploadImage(img)
        if (handle < 0) return
        drawNvgImage(handle, x.toFloat(), y.toFloat(), iw.toFloat(), ih.toFloat())
    }

    override fun drawImage(img: java.awt.Image?, x: Float, y: Float, w: Float, h: Float) {
        if (img !is BufferedImage) return
        val handle = getOrUploadImage(img)
        if (handle < 0) return
        drawNvgImage(handle, x, y, w, h)
    }

    /** NVG 이미지 핸들을 직접 받아 그립니다. */
    override fun drawNvgImage(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        nvgImagePattern(vg, x, y, w, h, 0f, handle, alpha, nvgPaint)
        nvgBeginPath(vg)
        nvgRect(vg, x, y, w, h)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    /** 이미지를 현재 좌표계(save/translate/rotate/scale 이 적용된 상태)의 (x, y) 에 그립니다. */
    override fun drawNvgImageTransformed(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        // 현재 transform 이 적용된 좌표계에서 이미지 패턴을 그린다.
        nvgImagePattern(vg, x, y, w, h, 0f, handle, alpha, nvgPaint)
        nvgBeginPath(vg)
        nvgRect(vg, x, y, w, h)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    /**
     * BufferedImage 를 NVG 이미지로 업로드하고 핸들을 반환합니다.
     * nvgCreateImageRGBA 를 사용해 NanoVG 가 텍스처를 직접 관리합니다.
     */
    override fun getOrUploadImage(img: BufferedImage): Int {
        val key = System.identityHashCode(img)
        val cached = imgCache.get(key)
        if (cached >= 0) return cached
        val w = img.width; val h = img.height
        val src = ensureArgbType(img)
        val pixels = (src.raster.dataBuffer as DataBufferInt).data
        // ARGB int → RGBA byte 변환
        val buf = MemoryUtil.memAlloc(pixels.size * 4)
        for (pixel in pixels) {
            buf.put(((pixel ushr 16) and 0xFF).toByte()) // R
            buf.put(((pixel ushr  8) and 0xFF).toByte()) // G
            buf.put(( pixel          and 0xFF).toByte()) // B
            buf.put(((pixel ushr 24) and 0xFF).toByte()) // A
        }
        buf.flip()
        val handle = nvgCreateImageRGBA(vg, w, h, 0, buf)
        MemoryUtil.memFree(buf)
        if (handle > 0) imgCache.put(key, handle)
        return handle
    }

    /** NVG 이미지를 해제합니다. 이미지 내용이 바뀌었을 때 호출하세요. */
    override fun invalidateImage(img: BufferedImage) {
        val key = System.identityHashCode(img)
        imgCache.remove(key)?.let { nvgDeleteImage(vg, it) }
    }

    private fun ensureArgbType(img: BufferedImage): BufferedImage {
        if (img.type == BufferedImage.TYPE_INT_ARGB ||
            img.type == BufferedImage.TYPE_INT_ARGB_PRE) return img
        val tmp = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        val g2 = tmp.createGraphics()
        g2.drawImage(img, 0, 0, null)
        g2.dispose()
        return tmp
    }

    // ── 그라디언트 ──────────────────────────────────────────────────────────
    override fun fillRadialGradient(
        x: Float, y: Float, w: Float, h: Float,
        cx: Float, cy: Float, inR: Float, outR: Float,
        innerColor: Color, outerColor: Color
    ) {
        nvgRGBAf(innerColor.red/255f, innerColor.green/255f, innerColor.blue/255f, innerColor.alpha/255f, nvgColor)
        nvgRGBAf(outerColor.red/255f, outerColor.green/255f, outerColor.blue/255f, outerColor.alpha/255f, nvgColor2)
        nvgRadialGradient(vg, cx, cy, inR, outR, nvgColor, nvgColor2, nvgPaint)
        nvgBeginPath(vg)
        nvgRect(vg, x, y, w, h)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    override fun fillRadialGradient(
        x: Float, y: Float, w: Float, h: Float,
        cx: Float, cy: Float, inR: Float, outR: Float,
        innerColor: RenderColor, outerColor: RenderColor
    ) {
        nvgRGBAf(innerColor.rf, innerColor.gf, innerColor.bf, innerColor.af, nvgColor)
        nvgRGBAf(outerColor.rf, outerColor.gf, outerColor.bf, outerColor.af, nvgColor2)
        nvgRadialGradient(vg, cx, cy, inR, outR, nvgColor, nvgColor2, nvgPaint)
        nvgBeginPath(vg); nvgRect(vg, x, y, w, h); nvgFillPaint(vg, nvgPaint); nvgFill(vg)
    }

    override fun fillLinearGradient(
        x: Float, y: Float, w: Float, h: Float,
        x0: Float, y0: Float, x1: Float, y1: Float,
        startColor: Color, endColor: Color
    ) {
        nvgRGBAf(startColor.red/255f, startColor.green/255f, startColor.blue/255f, startColor.alpha/255f, nvgColor)
        nvgRGBAf(endColor.red/255f,   endColor.green/255f,   endColor.blue/255f,   endColor.alpha/255f,   nvgColor2)
        nvgLinearGradient(vg, x0, y0, x1, y1, nvgColor, nvgColor2, nvgPaint)
        nvgBeginPath(vg)
        nvgRect(vg, x, y, w, h)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    override fun fillLinearGradient(
        x: Float, y: Float, w: Float, h: Float,
        x0: Float, y0: Float, x1: Float, y1: Float,
        startColor: RenderColor, endColor: RenderColor
    ) {
        nvgRGBAf(startColor.rf, startColor.gf, startColor.bf, startColor.af, nvgColor)
        nvgRGBAf(endColor.rf, endColor.gf, endColor.bf, endColor.af, nvgColor2)
        nvgLinearGradient(vg, x0, y0, x1, y1, nvgColor, nvgColor2, nvgPaint)
        nvgBeginPath(vg); nvgRect(vg, x, y, w, h); nvgFillPaint(vg, nvgPaint); nvgFill(vg)
    }

    override fun fillLinearGradientRoundRectTop(
        x: Float, y: Float, w: Float, h: Float, r: Float,
        x0: Float, y0: Float, x1: Float, y1: Float,
        startColor: RenderColor, endColor: RenderColor
    ) {
        nvgRGBAf(startColor.rf, startColor.gf, startColor.bf, startColor.af, nvgColor)
        nvgRGBAf(endColor.rf, endColor.gf, endColor.bf, endColor.af, nvgColor2)
        nvgLinearGradient(vg, x0, y0, x1, y1, nvgColor, nvgColor2, nvgPaint)
        nvgBeginPath(vg); nvgRoundedRectVarying(vg, x, y, w, h, r, r, 0f, 0f); nvgFillPaint(vg, nvgPaint); nvgFill(vg)
    }

    override fun fillLinearGradientRoundRect(
        x: Float, y: Float, w: Float, h: Float, r: Float,
        x0: Float, y0: Float, x1: Float, y1: Float,
        startColor: RenderColor, endColor: RenderColor
    ) {
        nvgRGBAf(startColor.rf, startColor.gf, startColor.bf, startColor.af, nvgColor)
        nvgRGBAf(endColor.rf, endColor.gf, endColor.bf, endColor.af, nvgColor2)
        nvgLinearGradient(vg, x0, y0, x1, y1, nvgColor, nvgColor2, nvgPaint)
        nvgBeginPath(vg); nvgRoundedRect(vg, x, y, w, h, r); nvgFillPaint(vg, nvgPaint); nvgFill(vg)
    }

    // ── 텍스트 정렬 변형 ─────────────────────────────────────────────────────
    override fun drawStringCentered(str: String, cx: Float, y: Float) {
        val f = font ?: return
        nvgFontFaceId(vg, FontRegistry.nvgFontId(vg, f.id))
        nvgFontSize(vg, f.size)
        nvgTextAlign(vg, NVG_ALIGN_CENTER or NVG_ALIGN_BASELINE)
        applyFillColor()
        nvgText(vg, cx, y, str)
    }

    override fun drawStringRight(str: String, x: Float, y: Float) {
        val f = font ?: return
        nvgFontFaceId(vg, FontRegistry.nvgFontId(vg, f.id))
        nvgFontSize(vg, f.size)
        nvgTextAlign(vg, NVG_ALIGN_RIGHT or NVG_ALIGN_BASELINE)
        applyFillColor()
        nvgText(vg, x, y, str)
    }

    override fun drawStringLeft(str: String, x: Float, y: Float) = drawString(str, x, y)

    // ── 폰트 블러 ────────────────────────────────────────────────────────────
    override fun setFontBlur(blur: Float) = nvgFontBlur(vg, blur)

    // ── 텍스트 측정 ─────────────────────────────────────────────────────────
    override fun measureStringWidth(str: String, f: DrawFont): Float {
        if (str.isEmpty()) return 0f
        nvgFontFaceId(vg, FontRegistry.nvgFontId(vg, f.id))
        nvgFontSize(vg, f.size)
        val bounds = FloatArray(4)
        return nvgTextBounds(vg, 0f, 0f, str, bounds)
    }

    override fun measureStringWidth(str: String): Float = font?.let { measureStringWidth(str, it) } ?: 0f

    // ── 박스 그라디언트 ──────────────────────────────────────────────────────
    override fun fillBoxGradientRect(
        x: Float, y: Float, w: Float, h: Float,
        r: Float, feather: Float,
        innerColor: Color, outerColor: Color
    ) {
        nvgRGBAf(innerColor.red/255f, innerColor.green/255f, innerColor.blue/255f, innerColor.alpha/255f, nvgColor)
        nvgRGBAf(outerColor.red/255f, outerColor.green/255f, outerColor.blue/255f, outerColor.alpha/255f, nvgColor2)
        nvgBoxGradient(vg, x, y, w, h, r, feather, nvgColor, nvgColor2, nvgPaint)
        nvgBeginPath(vg)
        nvgRect(vg, x - feather, y - feather, w + feather * 2, h + feather * 2)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    override fun fillBoxGradientRect(
        x: Float, y: Float, w: Float, h: Float,
        r: Float, feather: Float,
        innerColor: RenderColor, outerColor: RenderColor
    ) {
        nvgRGBAf(innerColor.rf, innerColor.gf, innerColor.bf, innerColor.af, nvgColor)
        nvgRGBAf(outerColor.rf, outerColor.gf, outerColor.bf, outerColor.af, nvgColor2)
        nvgBoxGradient(vg, x, y, w, h, r, feather, nvgColor, nvgColor2, nvgPaint)
        nvgBeginPath(vg); nvgRect(vg, x - feather, y - feather, w + feather * 2, h + feather * 2)
        nvgFillPaint(vg, nvgPaint); nvgFill(vg)
    }
}

/**
 * java.awt.FontMetrics 를 대체하는 NanoVG 기반 폰트 메트릭스.
 */
class NvgFontMetrics(private val vg: Long, private val font: DrawFont) : DrawFontMetrics {

    private val floatBuf = FloatArray(4)

    /** 주어진 문자열의 픽셀 너비를 반환합니다. */
    override fun stringWidth(str: String): Int {
        if (str.isEmpty()) return 0
        applyFont()
        return nvgTextBounds(vg, 0f, 0f, str, floatBuf).toInt()
    }

    private var _ascent   = Float.NaN
    private var _descent  = Float.NaN
    private var _lineH    = Float.NaN

    private fun ensureMetrics() {
        if (_ascent.isNaN()) {
            applyFont()
            val asc = FloatArray(1); val desc = FloatArray(1); val lineh = FloatArray(1)
            nvgTextMetrics(vg, asc, desc, lineh)
            _ascent  =  asc[0]
            _descent = -desc[0]  // NanoVG descent 는 음수
            _lineH   =  lineh[0]
        }
    }

    override val ascent:  Int get() { ensureMetrics(); return _ascent.toInt()  }
    override val descent: Int get() { ensureMetrics(); return _descent.toInt() }
    override val height:  Int get() { ensureMetrics(); return _lineH.toInt()   }

    private fun applyFont() {
        nvgFontFaceId(vg, FontRegistry.nvgFontId(vg, font.id))
        nvgFontSize(vg, font.size)
        nvgTextAlign(vg, NVG_ALIGN_LEFT or NVG_ALIGN_BASELINE)
    }
}
