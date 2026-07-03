package io.github.jwyoon1220.engine.render

import io.github.jwyoon1220.engine.DrawContext

/**
 * 렌더 커맨드 — 씬/시스템이 실제 그리기 없이 "무엇을 그릴지"만 기술합니다.
 *
 * ## 설계 원칙
 * - 모든 서브타입은 **불변 값 객체**입니다.
 * - 시스템은 [RenderCommand] 리스트를 `out` 버퍼에 추가하고 (produce),
 *   실제 실행은 Renderer(RendererBackend)가 담당합니다.
 * - `java.awt.*` 타입을 직접 쓰지 않습니다 — [RenderColor]를 사용하세요.
 *
 * ## 마이그레이션 탈출구
 * 기존 DrawContext 코드를 즉시 포팅하기 어려울 때는 [LegacyDrawContext]에
 * 람다로 감싸세요. ECS 이식이 완료되면 제거됩니다.
 *
 * ```kotlin
 * out += RenderCommand.LegacyDrawContext { g ->
 *     g.color = java.awt.Color.RED
 *     g.fillRect(0f, 0f, 100f, 50f)
 * }
 * ```
 */
sealed class RenderCommand {

    // ═══════════════════════════════════════════════════════════════════════════
    // 상태 제어
    // ═══════════════════════════════════════════════════════════════════════════

    /** 현재 변환/클립/알파 상태를 저장합니다. */
    data object Save : RenderCommand()

    /** 가장 최근에 [Save]한 상태를 복원합니다. */
    data object Restore : RenderCommand()

    /** 논리 좌표 (1280×720 기준) 이동 변환. */
    data class Translate(val x: Float, val y: Float) : RenderCommand()

    /** 스케일 변환. */
    data class Scale(val sx: Float, val sy: Float) : RenderCommand()

    /**
     * 이후 그리기를 [x],[y],[w],[h] 사각형 안으로 클리핑합니다.
     * [intersect]=true면 현재 클립 영역과 교집합을 적용합니다.
     */
    data class ClipRect(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val intersect: Boolean = true
    ) : RenderCommand()

    /** 클립 영역을 초기화합니다. */
    data object ResetClip : RenderCommand()

    /** 이후 그리기의 전역 알파를 [0.0–1.0]으로 설정합니다. */
    data class SetAlpha(val alpha: Float) : RenderCommand()

    // ═══════════════════════════════════════════════════════════════════════════
    // 채우기 (Fill)
    // ═══════════════════════════════════════════════════════════════════════════

    data class FillRect(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val color: RenderColor
    ) : RenderCommand()

    data class FillRoundRect(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val radius: Float, val color: RenderColor
    ) : RenderCommand()

    data class FillOval(
        val cx: Float, val cy: Float, val rx: Float, val ry: Float,
        val color: RenderColor
    ) : RenderCommand()

    /** 점 목록([points]: x0,y0, x1,y1, ...) 으로 정의된 다각형을 채웁니다. */
    data class FillPolygon(val points: FloatArray, val color: RenderColor) : RenderCommand() {
        override fun equals(other: Any?) = other is FillPolygon && points.contentEquals(other.points) && color == other.color
        override fun hashCode() = 31 * points.contentHashCode() + color.hashCode()
    }

    /**
     * 선형 그래디언트로 사각형을 채웁니다.
     * [startX],[startY] → [endX],[endY] 방향으로 [colorStart]에서 [colorEnd]로 변합니다.
     */
    data class FillLinearGradient(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val startX: Float, val startY: Float, val endX: Float, val endY: Float,
        val colorStart: RenderColor, val colorEnd: RenderColor
    ) : RenderCommand()

    // ═══════════════════════════════════════════════════════════════════════════
    // 외곽선 (Draw/Stroke)
    // ═══════════════════════════════════════════════════════════════════════════

    data class DrawRect(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val color: RenderColor, val strokeWidth: Float = 1f
    ) : RenderCommand()

    data class DrawRoundRect(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val radius: Float, val color: RenderColor, val strokeWidth: Float = 1f
    ) : RenderCommand()

    data class DrawLine(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val color: RenderColor, val strokeWidth: Float = 1f
    ) : RenderCommand()

    data class DrawOval(
        val cx: Float, val cy: Float, val rx: Float, val ry: Float,
        val color: RenderColor, val strokeWidth: Float = 1f
    ) : RenderCommand()

    // ═══════════════════════════════════════════════════════════════════════════
    // 텍스트
    // ═══════════════════════════════════════════════════════════════════════════

    data class DrawText(
        val text: String,
        val x: Float, val y: Float,
        val color: RenderColor,
        val fontId: String,
        val fontSize: Float
    ) : RenderCommand()

    data class DrawTextRight(
        val text: String,
        val x: Float, val y: Float,
        val color: RenderColor,
        val fontId: String,
        val fontSize: Float
    ) : RenderCommand()

    data class DrawTextCentered(
        val text: String,
        val cx: Float, val cy: Float,
        val color: RenderColor,
        val fontId: String,
        val fontSize: Float
    ) : RenderCommand()

    // ═══════════════════════════════════════════════════════════════════════════
    // 이미지
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 미리 로드된 NanoVG 이미지 핸들을 그립니다.
     * [imageHandle]: `nvgCreateImage*`로 얻은 Int 핸들.
     */
    data class DrawNvgImage(
        val imageHandle: Int,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val alpha: Float = 1f
    ) : RenderCommand()

    // ═══════════════════════════════════════════════════════════════════════════
    // 마이그레이션 탈출구
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 기존 [DrawContext] API를 직접 호출하는 람다를 래핑합니다.
     *
     * ECS 시스템으로 완전히 이식되기 전까지의 임시 경로입니다.
     * 이 커맨드를 사용하는 코드를 점진적으로 다른 [RenderCommand] 타입으로 교체하세요.
     */
    class LegacyDrawContext(val block: DrawContext.() -> Unit) : RenderCommand()
}

// ── RenderCommand 확장: DrawContext fallback 실행 ─────────────────────────────

/**
 * DrawContext 위에서 [RenderCommand]를 실행하는 fallback 확장.
 * NanoVGBackend가 없는 환경(테스트 등)이나 마이그레이션 과도기에 사용합니다.
 */
fun RenderCommand.executeOnDrawContext(g: DrawContext) {
    when (this) {
        is RenderCommand.LegacyDrawContext -> this.block(g)
        is RenderCommand.Save             -> g.save()
        is RenderCommand.Restore          -> g.restore()
        is RenderCommand.Translate        -> g.translate(x, y)
        is RenderCommand.SetAlpha         -> g.globalAlpha = alpha.coerceIn(0f, 1f)
        is RenderCommand.ClipRect         -> g.setClip(x, y, w, h)
        is RenderCommand.ResetClip        -> g.resetClip()
        is RenderCommand.FillRect -> {
            g.color = this.color.toAwt()
            g.fillRect(x, y, w, h)
        }
        is RenderCommand.FillRoundRect -> {
            g.color = this.color.toAwt()
            g.fillRoundRect(x, y, w, h, radius)
        }
        is RenderCommand.DrawLine -> {
            g.color = this.color.toAwt()
            g.stroke = java.awt.BasicStroke(strokeWidth)
            g.drawLine(x1, y1, x2, y2)
        }
        is RenderCommand.DrawText -> {
            g.color = this.color.toAwt()
            // fontId/fontSize: 호출자가 g.font를 미리 설정했다고 가정 (fallback 한계)
            g.drawString(text, x, y)
        }
        is RenderCommand.DrawTextCentered -> {
            g.color = this.color.toAwt()
            g.drawStringCentered(text, cx, cy)
        }
        is RenderCommand.DrawTextRight -> {
            g.color = this.color.toAwt()
            g.drawStringRight(text, x, y)
        }
        is RenderCommand.DrawNvgImage -> {
            g.drawNvgImage(imageHandle, x, y, w, h, alpha)
        }
        else -> { /* 지원 안 됨: 무시 */ }
    }
}

/** 마이그레이션 기간 한정 — RenderColor → java.awt.Color 변환. */
fun RenderColor.toAwt(): java.awt.Color = java.awt.Color(r, g, b, a)
