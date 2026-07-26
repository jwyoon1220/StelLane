package io.github.jwyoon1220.engine

import io.github.jwyoon1220.engine.render.RenderColor
import java.awt.Color
import java.awt.Rectangle
import java.awt.Stroke
import java.awt.image.BufferedImage

/**
 * java.awt.Graphics2D 에 가까운 2D 드로잉 API — 백엔드 무관 인터페이스.
 *
 * 구현체:
 * - [NvgDrawContext]: NanoVG + OpenGL 기반 (기본, `NanoVGBackend`가 소유)
 * - `VulkanDrawContext` (engine/vulkan): Vulkan 기반 2D 파이프라인
 *
 * 씬은 이 인터페이스 타입으로만 그리기 코드를 작성하므로([RenderCommand.LegacyDrawContext]의
 * `DrawContext.() -> Unit` 람다가 대표적 예), 어떤 백엔드가 실제로 그리는지 신경 쓸 필요가 없습니다.
 *
 * 메인 스레드(GLFW 루프)에서만 사용하세요.
 * 프레임 시작 시 [beginFrame], 종료 시 [endFrame] 을 호출해야 합니다.
 *
 * ## 이 인터페이스에서 의도적으로 뺀 것
 * 실사용 조사 결과 호출부가 하나도 없어서 제외했습니다: `composite`(0회), `clip: Shape?`
 * getter/setter(0회), 범용 `fill(Shape)`/`draw(Shape)` PathIterator 디스패치(0회, 베지어/쿼드
 * 곡선 포함), `setRenderingHint`/`getRenderingHint`(0회). 필요해지면 그때 추가하세요.
 */
interface DrawContext {
    /** 논리 해상도 너비 (픽셀) */
    val width: Int
    /** 논리 해상도 높이 (픽셀) */
    val height: Int

    var color: Color
    var renderColor: RenderColor
    var font: DrawFont?
    /** 전역 알파 (0f~1f). */
    var globalAlpha: Float
    /** 선 두께 등 스트로크 속성. BasicStroke만 실질적으로 해석됩니다. */
    var stroke: Stroke
    /** Graphics2D.getClipBounds() 호환 — 항상 전체 프레임 영역을 반환합니다. */
    val clipBounds: Rectangle

    // ── 프레임 생명주기 ─────────────────────────────────────────────────────
    fun beginFrame(fbWidth: Int, fbHeight: Int, devicePixelRatio: Float = 1f)
    fun endFrame()

    // ── 상태 저장/복원 ──────────────────────────────────────────────────────
    fun save()
    fun restore()
    fun scoped(block: DrawContext.() -> Unit) {
        save(); try { block() } finally { restore() }
    }

    // ── 좌표 변환 ───────────────────────────────────────────────────────────
    fun translate(tx: Double, ty: Double)
    fun translate(tx: Float,  ty: Float)
    fun translate(tx: Int,    ty: Int)
    fun scale(sx: Double, sy: Double)
    fun scale(sx: Float,  sy: Float)
    /** 회전 단위는 라디안(radians)입니다. */
    fun rotate(theta: Double)
    fun rotate(theta: Float)

    // ── 클립 / 시저 ─────────────────────────────────────────────────────────
    fun setClip(x: Int, y: Int, w: Int, h: Int)
    fun setClip(x: Float, y: Float, w: Float, h: Float)
    fun resetClip()

    // ── FontMetrics 호환 ────────────────────────────────────────────────────
    val fontMetrics: DrawFontMetrics
    fun getFontMetrics(f: DrawFont): DrawFontMetrics

    // ── 사각형 ──────────────────────────────────────────────────────────────
    fun fillRect(x: Int, y: Int, w: Int, h: Int)
    fun fillRect(x: Float, y: Float, w: Float, h: Float)
    fun drawRect(x: Int, y: Int, w: Int, h: Int)
    fun drawRect(x: Float, y: Float, w: Float, h: Float)

    // ── 둥근 사각형 ─────────────────────────────────────────────────────────
    fun fillRoundRect(x: Int, y: Int, w: Int, h: Int, arcW: Int, arcH: Int)
    fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float)
    fun drawRoundRect(x: Int, y: Int, w: Int, h: Int, arcW: Int, arcH: Int)
    fun drawRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float)

    // ── 선 ──────────────────────────────────────────────────────────────────
    fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int)
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float)

    // ── 타원 ────────────────────────────────────────────────────────────────
    fun fillOval(x: Int, y: Int, w: Int, h: Int)
    fun drawOval(x: Int, y: Int, w: Int, h: Int)
    fun fillCircle(cx: Float, cy: Float, r: Float)

    // ── 다각형 ──────────────────────────────────────────────────────────────
    fun fillPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int)
    fun drawPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int)

    // ── 텍스트 ──────────────────────────────────────────────────────────────
    /** 텍스트를 (x, y) 에 그립니다. y 는 baseline 기준입니다 (Graphics2D 호환). */
    fun drawString(str: String, x: Int, y: Int)
    fun drawString(str: String, x: Float, y: Float)
    /** 텍스트를 가운데 정렬로 그립니다. cx 가 중심 x 좌표. */
    fun drawStringCentered(str: String, cx: Float, y: Float)
    /** 텍스트를 오른쪽 정렬로 그립니다. x 가 오른쪽 끝 좌표. */
    fun drawStringRight(str: String, x: Float, y: Float)
    /** 텍스트를 왼쪽 정렬로 그립니다 (drawString 별칭, 일관성 유지용). */
    fun drawStringLeft(str: String, x: Float, y: Float)
    /** 폰트 블러 반경을 설정합니다. 0f 로 초기화하세요. */
    fun setFontBlur(blur: Float)
    fun measureStringWidth(str: String, f: DrawFont): Float
    fun measureStringWidth(str: String): Float

    // ── 이미지 그리기 ────────────────────────────────────────────────────────
    fun drawImage(
        img: java.awt.Image?,
        x: Int, y: Int, w: Int = -1, h: Int = -1,
        observer: java.awt.image.ImageObserver? = null
    )
    fun drawImage(img: java.awt.Image?, x: Float, y: Float, w: Float, h: Float)
    /** 백엔드가 관리하는 불투명 이미지 핸들("Nvg"는 역사적 이름 — 백엔드마다 다르게 해석)을 그립니다. */
    fun drawNvgImage(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f)
    /** 이미지를 현재 좌표계(save/translate/rotate/scale 이 적용된 상태)의 (x, y) 에 그립니다. */
    fun drawNvgImageTransformed(handle: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f)
    /** BufferedImage 를 백엔드 텍스처로 업로드하고 핸들을 반환합니다(캐시됨). */
    fun getOrUploadImage(img: BufferedImage): Int
    /** 캐시된 이미지를 해제합니다. 이미지 내용이 바뀌었을 때 호출하세요. */
    fun invalidateImage(img: BufferedImage)

    // ── 그라디언트 ──────────────────────────────────────────────────────────
    fun fillRadialGradient(
        x: Float, y: Float, w: Float, h: Float,
        cx: Float, cy: Float, inR: Float, outR: Float,
        innerColor: Color, outerColor: Color
    )
    fun fillRadialGradient(
        x: Float, y: Float, w: Float, h: Float,
        cx: Float, cy: Float, inR: Float, outR: Float,
        innerColor: RenderColor, outerColor: RenderColor
    )
    fun fillLinearGradient(
        x: Float, y: Float, w: Float, h: Float,
        x0: Float, y0: Float, x1: Float, y1: Float,
        startColor: Color, endColor: Color
    )
    fun fillLinearGradient(
        x: Float, y: Float, w: Float, h: Float,
        x0: Float, y0: Float, x1: Float, y1: Float,
        startColor: RenderColor, endColor: RenderColor
    )
    /** 선형 그라디언트로 상단 모서리만 둥근 사각형을 채웁니다. */
    fun fillLinearGradientRoundRectTop(
        x: Float, y: Float, w: Float, h: Float, r: Float,
        x0: Float, y0: Float, x1: Float, y1: Float,
        startColor: RenderColor, endColor: RenderColor
    )
    /** 선형 그라디언트로 둥근 사각형을 채웁니다. */
    fun fillLinearGradientRoundRect(
        x: Float, y: Float, w: Float, h: Float, r: Float,
        x0: Float, y0: Float, x1: Float, y1: Float,
        startColor: RenderColor, endColor: RenderColor
    )
    /**
     * 둥근 사각형 안쪽에서 바깥쪽으로 퍼지는 박스 그라디언트를 채웁니다.
     * 커버 이미지 테두리 글로우 등에 활용합니다.
     */
    fun fillBoxGradientRect(
        x: Float, y: Float, w: Float, h: Float,
        r: Float, feather: Float,
        innerColor: Color, outerColor: Color
    )
    fun fillBoxGradientRect(
        x: Float, y: Float, w: Float, h: Float,
        r: Float, feather: Float,
        innerColor: RenderColor, outerColor: RenderColor
    )
}

/** java.awt.FontMetrics 를 대체하는 폰트 메트릭스 인터페이스. */
interface DrawFontMetrics {
    /** 주어진 문자열의 픽셀 너비를 반환합니다. */
    fun stringWidth(str: String): Int
    val ascent: Int
    val descent: Int
    val height: Int
}
