package io.github.jwyoon1220.engine

import org.lwjgl.nanovg.NVGColor
import org.lwjgl.nanovg.NVGPaint
import org.lwjgl.nanovg.NanoVG.*
import org.lwjgl.nanovg.NanoVGGL3.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.system.MemoryStack
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Composite
import java.awt.Rectangle
import java.awt.Shape
import java.awt.Stroke
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import org.lwjgl.system.MemoryUtil
import io.github.jwyoon1220.engine.render.RenderColor

/**
 * NanoVG 위에 java.awt.Graphics2D 에 가까운 API 를 제공하는 렌더링 컨텍스트.
 *
 * 메인 스레드(GLFW 루프)에서만 사용하세요.
 * 프레임 시작 시 [beginFrame], 종료 시 [endFrame] 을 호출해야 합니다.
 */
class DrawContext(
    val vg: Long,
    /** 논리 해상도 너비 (픽셀) */
    val width: Int,
    /** 논리 해상도 높이 (픽셀) */
    val height: Int
) {
    // ── 상태 ────────────────────────────────────────────────────────────────
    private var _color: Color? = null
    var color: Color
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
    var renderColor: RenderColor
        get() = _renderColor
        set(value) {
            _renderColor = value
            _color = null
        }
    var font: DrawFont? = null

    private var _alpha: Float = 1f          // nvgGlobalAlpha 와 동기화
    private var _strokeWidth: Float = 1f    // nvgStrokeWidth 와 동기화

    /** AlphaComposite.SRC_OVER 에서 알파를 추출해 전역 알파로 설정합니다. */
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
    var stroke: Stroke = BasicStroke(1f)
        set(value) {
            field = value
            _strokeWidth = when (value) {
                is BasicStroke -> value.lineWidth
                else           -> 1f
            }
        }

    /** Graphics2D.getClipBounds() 호환 — 현재는 전체 프레임 영역을 반환합니다. */
    val clipBounds: Rectangle get() = Rectangle(0, 0, width, height)

    // 클립 스택 (scissor)
    private data class ClipRect(val x: Float, val y: Float, val w: Float, val h: Float)
    private val clipStack = ArrayDeque<ClipRect?>()
    private var currentClip: ClipRect? = null

    /** Graphics2D.clip 프로퍼티 호환 (get = 현재 scissor 영역, set = 새 scissor 적용). */
    var clip: Shape?
        get() = currentClip?.let { java.awt.Rectangle(it.x.toInt(), it.y.toInt(), it.w.toInt(), it.h.toInt()) }
        set(value) {
            when (value) {
                null -> resetClip()
                is RoundRectangle2D -> setClip(
                    value.x.toFloat(), value.y.toFloat(),
                    value.width.toFloat(), value.height.toFloat()
                )
                is Rectangle2D -> setClip(
                    value.x.toFloat(), value.y.toFloat(),
                    value.width.toFloat(), value.height.toFloat()
                )
                is Rectangle -> setClip(
                    value.x.toFloat(), value.y.toFloat(),
                    value.width.toFloat(), value.height.toFloat()
                )
                else -> resetClip()  // 지원하지 않는 Shape → 클립 해제
            }
        }

    // ── BufferedImage → NVG 이미지 캐시 ────────────────────────────────────
    private val imgCache = HashMap<Int, Int>()  // identityHashCode → nvgImgHandle

    // ── NVGColor 재사용 버퍼 ───────────────────────────────────────────────
    private val nvgColor  = NVGColor.create()
    private val nvgColor2 = NVGColor.create()
    private val nvgPaint  = NVGPaint.create()
    private val nvgPaint2 = NVGPaint.create()

    // ── 프레임 생명주기 ─────────────────────────────────────────────────────
    fun beginFrame(fbWidth: Int, fbHeight: Int, devicePixelRatio: Float = 1f) {
        nvgBeginFrame(vg, fbWidth.toFloat(), fbHeight.toFloat(), devicePixelRatio)
        _alpha = 1f
        nvgGlobalAlpha(vg, 1f)
        currentClip = null
        clipStack.clear()
    }

    fun endFrame() {
        nvgEndFrame(vg)
    }

    // ── 상태 저장/복원 ──────────────────────────────────────────────────────
    fun save() {
        nvgSave(vg)
        clipStack.addLast(currentClip)
    }

    fun restore() {
        nvgRestore(vg)
        currentClip = clipStack.removeLastOrNull() ?: null
        _alpha = 1f  // nvgRestore 가 globalAlpha 도 복원하므로 내부 상태 동기화
    }

    inline fun scoped(block: DrawContext.() -> Unit) {
        save(); try { block() } finally { restore() }
    }

    // ── 좌표 변환 ───────────────────────────────────────────────────────────
    fun translate(tx: Double, ty: Double) = nvgTranslate(vg, tx.toFloat(), ty.toFloat())
    fun translate(tx: Float,  ty: Float ) = nvgTranslate(vg, tx, ty)
    fun translate(tx: Int,    ty: Int   ) = nvgTranslate(vg, tx.toFloat(), ty.toFloat())
    fun scale    (sx: Double, sy: Double) = nvgScale(vg, sx.toFloat(), sy.toFloat())
    fun scale    (sx: Float,  sy: Float ) = nvgScale(vg, sx, sy)
    /** NanoVG 의 회전 단위는 라디안(radians)입니다. */
    fun rotate   (theta: Double)          = nvgRotate(vg, theta.toFloat())
    fun rotate   (theta: Float )          = nvgRotate(vg, theta)

    // ── 클립 / 시저 ─────────────────────────────────────────────────────────
    fun setClip(x: Int, y: Int, w: Int, h: Int) = setClip(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
    fun setClip(x: Float, y: Float, w: Float, h: Float) {
        nvgResetScissor(vg)
        nvgScissor(vg, x, y, w, h)
        currentClip = ClipRect(x, y, w, h)
    }
    fun resetClip() {
        nvgResetScissor(vg)
        currentClip = null
    }

    // ── 렌더링 힌트 (no-op) ─────────────────────────────────────────────────
    fun setRenderingHint(key: Any?, value: Any?) = Unit
    fun getRenderingHint(key: Any?): Any? = null

    // ── FontMetrics 호환 ────────────────────────────────────────────────────
    val fontMetrics: DrawFontMetrics get() = getFontMetrics(font ?: FontRegistry.regular)

    fun getFontMetrics(f: DrawFont): DrawFontMetrics = DrawFontMetrics(vg, f)

    // ── 내부: 색상 설정 ─────────────────────────────────────────────────────
    private fun applyFillColor(c: Color = color) {
        if (c === color && _color == null) {
            nvgRGBAf(_renderColor.rf, _renderColor.gf, _renderColor.bf, _renderColor.af, nvgColor)
        } else {
            nvgRGBAf(c.red / 255f, c.green / 255f, c.blue / 255f, c.alpha / 255f, nvgColor)
        }
        nvgFillColor(vg, nvgColor)
    }

    private fun applyStrokeColor(c: Color = color) {
        if (c === color && _color == null) {
            nvgRGBAf(_renderColor.rf, _renderColor.gf, _renderColor.bf, _renderColor.af, nvgColor)
        } else {
            nvgRGBAf(c.red / 255f, c.green / 255f, c.blue / 255f, c.alpha / 255f, nvgColor)
        }
        nvgStrokeColor(vg, nvgColor)
        nvgStrokeWidth(vg, _strokeWidth)
    }

    // ── 사각형 ──────────────────────────────────────────────────────────────
    fun fillRect(x: Int, y: Int, w: Int, h: Int) {
        applyFillColor()
        nvgBeginPath(vg); nvgRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()); nvgFill(vg)
    }
    fun fillRect(x: Float, y: Float, w: Float, h: Float) {
        applyFillColor()
        nvgBeginPath(vg); nvgRect(vg, x, y, w, h); nvgFill(vg)
    }
    fun drawRect(x: Int, y: Int, w: Int, h: Int) {
        applyStrokeColor()
        nvgBeginPath(vg); nvgRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()); nvgStroke(vg)
    }
    fun drawRect(x: Float, y: Float, w: Float, h: Float) {
        applyStrokeColor()
        nvgBeginPath(vg); nvgRect(vg, x, y, w, h); nvgStroke(vg)
    }

    // ── 둥근 사각형 ─────────────────────────────────────────────────────────
    fun fillRoundRect(x: Int, y: Int, w: Int, h: Int, arcW: Int, arcH: Int) {
        applyFillColor()
        val r = minOf(arcW, arcH) / 2f
        nvgBeginPath(vg); nvgRoundedRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), r); nvgFill(vg)
    }
    fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        applyFillColor()
        nvgBeginPath(vg); nvgRoundedRect(vg, x, y, w, h, r); nvgFill(vg)
    }
    fun drawRoundRect(x: Int, y: Int, w: Int, h: Int, arcW: Int, arcH: Int) {
        applyStrokeColor()
        val r = minOf(arcW, arcH) / 2f
        nvgBeginPath(vg); nvgRoundedRect(vg, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), r); nvgStroke(vg)
    }
    fun drawRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        applyStrokeColor()
        nvgBeginPath(vg); nvgRoundedRect(vg, x, y, w, h, r); nvgStroke(vg)
    }

    // ── 선 ──────────────────────────────────────────────────────────────────
    fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int) {
        applyStrokeColor()
        nvgBeginPath(vg)
        nvgMoveTo(vg, x1.toFloat(), y1.toFloat())
        nvgLineTo(vg, x2.toFloat(), y2.toFloat())
        nvgStroke(vg)
    }
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        applyStrokeColor()
        nvgBeginPath(vg)
        nvgMoveTo(vg, x1, y1)
        nvgLineTo(vg, x2, y2)
        nvgStroke(vg)
    }

    // ── 타원 ────────────────────────────────────────────────────────────────
    fun fillOval(x: Int, y: Int, w: Int, h: Int) {
        applyFillColor()
        val cx = x + w / 2f; val cy = y + h / 2f
        nvgBeginPath(vg); nvgEllipse(vg, cx, cy, w / 2f, h / 2f); nvgFill(vg)
    }
    fun drawOval(x: Int, y: Int, w: Int, h: Int) {
        applyStrokeColor()
        val cx = x + w / 2f; val cy = y + h / 2f
        nvgBeginPath(vg); nvgEllipse(vg, cx, cy, w / 2f, h / 2f); nvgStroke(vg)
    }
    fun fillCircle(cx: Float, cy: Float, r: Float) {
        applyFillColor()
        nvgBeginPath(vg); nvgCircle(vg, cx, cy, r); nvgFill(vg)
    }

    // ── 다각형 ──────────────────────────────────────────────────────────────
    fun fillPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) {
        if (nPoints < 2) return
        applyFillColor()
        nvgBeginPath(vg)
        nvgMoveTo(vg, xPoints[0].toFloat(), yPoints[0].toFloat())
        for (i in 1 until nPoints) nvgLineTo(vg, xPoints[i].toFloat(), yPoints[i].toFloat())
        nvgClosePath(vg); nvgFill(vg)
    }
    fun drawPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) {
        if (nPoints < 2) return
        applyStrokeColor()
        nvgBeginPath(vg)
        nvgMoveTo(vg, xPoints[0].toFloat(), yPoints[0].toFloat())
        for (i in 1 until nPoints) nvgLineTo(vg, xPoints[i].toFloat(), yPoints[i].toFloat())
        nvgClosePath(vg); nvgStroke(vg)
    }

    // ── Shape 디스패치 ──────────────────────────────────────────────────────
    /** java.awt.Shape 을 NanoVG 로 채웁니다. */
    fun fill(shape: Shape) = shapeOp(shape, fill = true)

    /** java.awt.Shape 의 외곽선을 NanoVG 로 그립니다. */
    fun draw(shape: Shape) = shapeOp(shape, fill = false)

    private fun shapeOp(shape: Shape, fill: Boolean) {
        when (shape) {
            is RoundRectangle2D -> {
                val r = (minOf(shape.arcWidth, shape.arcHeight) / 2).toFloat()
                if (fill) {
                    applyFillColor()
                    nvgBeginPath(vg)
                    nvgRoundedRect(vg, shape.x.toFloat(), shape.y.toFloat(),
                        shape.width.toFloat(), shape.height.toFloat(), r)
                    nvgFill(vg)
                } else {
                    applyStrokeColor()
                    nvgBeginPath(vg)
                    nvgRoundedRect(vg, shape.x.toFloat(), shape.y.toFloat(),
                        shape.width.toFloat(), shape.height.toFloat(), r)
                    nvgStroke(vg)
                }
            }
            is Rectangle2D -> {
                if (fill) {
                    applyFillColor()
                    nvgBeginPath(vg)
                    nvgRect(vg, shape.x.toFloat(), shape.y.toFloat(),
                        shape.width.toFloat(), shape.height.toFloat())
                    nvgFill(vg)
                } else {
                    applyStrokeColor()
                    nvgBeginPath(vg)
                    nvgRect(vg, shape.x.toFloat(), shape.y.toFloat(),
                        shape.width.toFloat(), shape.height.toFloat())
                    nvgStroke(vg)
                }
            }
            is Ellipse2D -> {
                val cx = (shape.x + shape.width  / 2).toFloat()
                val cy = (shape.y + shape.height / 2).toFloat()
                if (fill) {
                    applyFillColor()
                    nvgBeginPath(vg)
                    nvgEllipse(vg, cx, cy, (shape.width / 2).toFloat(), (shape.height / 2).toFloat())
                    nvgFill(vg)
                } else {
                    applyStrokeColor()
                    nvgBeginPath(vg)
                    nvgEllipse(vg, cx, cy, (shape.width / 2).toFloat(), (shape.height / 2).toFloat())
                    nvgStroke(vg)
                }
            }
            else -> {
                // 범용 PathIterator 디스패치
                val pi = shape.getPathIterator(null)
                val coords = FloatArray(6)
                nvgBeginPath(vg)
                while (!pi.isDone) {
                    when (pi.currentSegment(coords)) {
                        java.awt.geom.PathIterator.SEG_MOVETO  -> nvgMoveTo(vg, coords[0], coords[1])
                        java.awt.geom.PathIterator.SEG_LINETO  -> nvgLineTo(vg, coords[0], coords[1])
                        java.awt.geom.PathIterator.SEG_CUBICTO -> nvgBezierTo(vg, coords[0], coords[1],
                            coords[2], coords[3], coords[4], coords[5])
                        java.awt.geom.PathIterator.SEG_QUADTO  -> nvgQuadTo(vg, coords[0], coords[1],
                            coords[2], coords[3])
                        java.awt.geom.PathIterator.SEG_CLOSE   -> nvgClosePath(vg)
                    }
                    pi.next()
                }
                if (fill) { applyFillColor(); nvgFill(vg) }
                else      { applyStrokeColor(); nvgStroke(vg) }
            }
        }
    }

    // ── 텍스트 ──────────────────────────────────────────────────────────────
    /**
     * 텍스트를 (x, y) 에 그립니다. y 는 baseline 기준입니다 (Graphics2D 호환).
     */
    fun drawString(str: String, x: Int, y: Int) = drawString(str, x.toFloat(), y.toFloat())
    fun drawString(str: String, x: Float, y: Float) {
        val f = font ?: return
        nvgFontFaceId(vg, f.id)
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
    fun drawImage(
        img: java.awt.Image?,
        x: Int, y: Int, w: Int = -1, h: Int = -1,
        observer: java.awt.image.ImageObserver? = null
    ) {
        if (img !is BufferedImage) return
        val iw = if (w < 0) img.width  else w
        val ih = if (h < 0) img.height else h
        val handle = getOrUploadImage(img)
        if (handle < 0) return
        drawNvgImage(handle, x.toFloat(), y.toFloat(), iw.toFloat(), ih.toFloat())
    }

    fun drawImage(img: java.awt.Image?, x: Float, y: Float, w: Float, h: Float) {
        if (img !is BufferedImage) return
        val handle = getOrUploadImage(img)
        if (handle < 0) return
        drawNvgImage(handle, x, y, w, h)
    }

    /** NVG 이미지 핸들을 직접 받아 그립니다. */
    fun drawNvgImage(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f) {
        nvgImagePattern(vg, x, y, w, h, 0f, handle, alpha, nvgPaint)
        nvgBeginPath(vg)
        nvgRect(vg, x, y, w, h)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    /** 이미지를 현재 좌표계(save/translate/rotate/scale 이 적용된 상태)의 (x, y) 에 그립니다. */
    fun drawNvgImageTransformed(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f) {
        // 현재 transform 이 적용된 좌표계에서 이미지 패턴을 그린다.
        nvgImagePattern(vg, x, y, w, h, 0f, handle, alpha, nvgPaint)
        nvgBeginPath(vg)
        nvgRect(vg, x, y, w, h)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    /** BufferedImage 를 NVG 텍스처로 업로드(최초) 혹은 캐시에서 찾아 핸들을 반환합니다. */
    fun getOrUploadImage(img: BufferedImage): Int {
        val key = System.identityHashCode(img)
        imgCache[key]?.let { return it }
        // ARGB → RGBA 바이트 순서로 변환 후 nvgCreateImageRGBA 로 업로드
        val w = img.width; val h = img.height
        val buf = toRGBABuffer(img)
        val handle = nvgCreateImageRGBA(vg, w, h, NVG_IMAGE_PREMULTIPLIED, buf)
        MemoryUtil.memFree(buf)
        if (handle >= 0) imgCache[key] = handle
        return handle
    }

    /** 캐시에서 NVG 이미지 핸들을 삭제합니다. 이미지 내용이 바뀌었을 때 호출하세요. */
    fun invalidateImage(img: BufferedImage) {
        val key = System.identityHashCode(img)
        imgCache.remove(key)?.let { nvgDeleteImage(vg, it) }
    }

    // ── 그라디언트 ──────────────────────────────────────────────────────────
    /** NanoVG 방사형 그라디언트로 사각형을 채웁니다. */
    fun fillRadialGradient(
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

    /** NanoVG 방사형 그라디언트로 사각형을 채웁니다 (RenderColor 오버로드). */
    fun fillRadialGradient(
        x: Float, y: Float, w: Float, h: Float,
        cx: Float, cy: Float, inR: Float, outR: Float,
        innerColor: RenderColor, outerColor: RenderColor
    ) {
        nvgRGBAf(innerColor.rf, innerColor.gf, innerColor.bf, innerColor.af, nvgColor)
        nvgRGBAf(outerColor.rf, outerColor.gf, outerColor.bf, outerColor.af, nvgColor2)
        nvgRadialGradient(vg, cx, cy, inR, outR, nvgColor, nvgColor2, nvgPaint)
        nvgBeginPath(vg); nvgRect(vg, x, y, w, h); nvgFillPaint(vg, nvgPaint); nvgFill(vg)
    }

    /** NanoVG 선형 그라디언트로 사각형을 채웁니다. */
    fun fillLinearGradient(
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

    /** NanoVG 선형 그라디언트로 사각형을 채웁니다 (RenderColor 오버로드). */
    fun fillLinearGradient(
        x: Float, y: Float, w: Float, h: Float,
        x0: Float, y0: Float, x1: Float, y1: Float,
        startColor: RenderColor, endColor: RenderColor
    ) {
        nvgRGBAf(startColor.rf, startColor.gf, startColor.bf, startColor.af, nvgColor)
        nvgRGBAf(endColor.rf, endColor.gf, endColor.bf, endColor.af, nvgColor2)
        nvgLinearGradient(vg, x0, y0, x1, y1, nvgColor, nvgColor2, nvgPaint)
        nvgBeginPath(vg); nvgRect(vg, x, y, w, h); nvgFillPaint(vg, nvgPaint); nvgFill(vg)
    }

    // ── 텍스트 정렬 변형 ─────────────────────────────────────────────────────

    /** 텍스트를 가운데 정렬로 그립니다. cx 가 중심 x 좌표. */
    fun drawStringCentered(str: String, cx: Float, y: Float) {
        val f = font ?: return
        nvgFontFaceId(vg, f.id)
        nvgFontSize(vg, f.size)
        nvgTextAlign(vg, NVG_ALIGN_CENTER or NVG_ALIGN_BASELINE)
        applyFillColor()
        nvgText(vg, cx, y, str)
    }

    /** 텍스트를 오른쪽 정렬로 그립니다. x 가 오른쪽 끝 좌표. */
    fun drawStringRight(str: String, x: Float, y: Float) {
        val f = font ?: return
        nvgFontFaceId(vg, f.id)
        nvgFontSize(vg, f.size)
        nvgTextAlign(vg, NVG_ALIGN_RIGHT or NVG_ALIGN_BASELINE)
        applyFillColor()
        nvgText(vg, x, y, str)
    }

    /** 텍스트를 왼쪽 정렬로 그립니다 (drawString 별칭, 일관성 유지용). */
    fun drawStringLeft(str: String, x: Float, y: Float) = drawString(str, x, y)

    // ── 폰트 블러 ────────────────────────────────────────────────────────────

    /** NanoVG 폰트 블러 반경을 설정합니다. 0f 로 초기화하세요. */
    fun setFontBlur(blur: Float) = nvgFontBlur(vg, blur)

    // ── 텍스트 측정 ─────────────────────────────────────────────────────────

    /** 주어진 폰트로 문자열 너비를 측정합니다. */
    fun measureStringWidth(str: String, f: DrawFont): Float {
        if (str.isEmpty()) return 0f
        nvgFontFaceId(vg, f.id)
        nvgFontSize(vg, f.size)
        val bounds = FloatArray(4)
        return nvgTextBounds(vg, 0f, 0f, str, bounds)
    }

    /** 현재 설정된 font 로 문자열 너비를 측정합니다. */
    fun measureStringWidth(str: String): Float = font?.let { measureStringWidth(str, it) } ?: 0f

    // ── 박스 그라디언트 ──────────────────────────────────────────────────────

    /**
     * 둥근 사각형 안쪽에서 바깥쪽으로 퍼지는 박스 그라디언트를 채웁니다.
     * 커버 이미지 테두리 글로우 등에 활용합니다.
     *
     * @param x, y, w, h   사각형 영역
     * @param r             모서리 반경
     * @param feather       흐림 폭 (px)
     * @param innerColor    안쪽 색
     * @param outerColor    바깥쪽 색
     */
    fun fillBoxGradientRect(
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

    /** 박스 그라디언트를 채웁니다 (RenderColor 오버로드). */
    fun fillBoxGradientRect(
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

    // ── 내부 유틸 ───────────────────────────────────────────────────────────
    private fun toRGBABuffer(img: BufferedImage): ByteBuffer {
        val w = img.width; val h = img.height
        val buf = MemoryUtil.memAlloc(w * h * 4)
        // TYPE_INT_ARGB → RGBA bytes
        val converted = if (img.type == BufferedImage.TYPE_INT_ARGB ||
                            img.type == BufferedImage.TYPE_INT_RGB  ||
                            img.type == BufferedImage.TYPE_INT_ARGB_PRE) {
            img
        } else {
            val tmp = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val tg = tmp.createGraphics()
            tg.drawImage(img, 0, 0, null)
            tg.dispose()
            tmp
        }
        val pixels = (converted.raster.dataBuffer as DataBufferInt).data
        for (pixel in pixels) {
            buf.put(((pixel shr 16) and 0xFF).toByte())  // R
            buf.put(((pixel shr  8) and 0xFF).toByte())  // G
            buf.put(( pixel        and 0xFF).toByte())   // B
            buf.put(((pixel shr 24) and 0xFF).toByte())  // A
        }
        buf.flip()
        return buf
    }
}

/**
 * java.awt.FontMetrics 를 대체하는 NanoVG 기반 폰트 메트릭스.
 */
class DrawFontMetrics(private val vg: Long, private val font: DrawFont) {

    private val floatBuf = FloatArray(4)

    /** 주어진 문자열의 픽셀 너비를 반환합니다. */
    fun stringWidth(str: String): Int {
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

    val ascent:  Int get() { ensureMetrics(); return _ascent.toInt()  }
    val descent: Int get() { ensureMetrics(); return _descent.toInt() }
    val height:  Int get() { ensureMetrics(); return _lineH.toInt()   }

    private fun applyFont() {
        nvgFontFaceId(vg, font.id)
        nvgFontSize(vg, font.size)
        nvgTextAlign(vg, NVG_ALIGN_LEFT or NVG_ALIGN_BASELINE)
    }
}
