package io.github.jwyoon1220.engine.render

/**
 * 엔진 고유 색상 타입 — java.awt.Color 의존을 완전히 제거합니다.
 *
 * 색상은 ARGB 형태로 32비트 Int에 패킹됩니다.
 * `@JvmInline value class`로 선언해 런타임 박싱이 없습니다.
 *
 * ## 생성 방법
 * ```kotlin
 * val red   = RenderColor.of(255, 0, 0)
 * val semi  = RenderColor.of(0, 128, 255, 180)
 * val hex   = RenderColor.fromHex(0xFF_64_B4_FF)
 * val white = RenderColor.WHITE
 * ```
 *
 * ## java.awt.Color 변환 (마이그레이션 기간 한정)
 * ```kotlin
 * val awt = java.awt.Color(100, 180, 255)
 * val rc  = RenderColor.fromAwt(awt)
 * ```
 */
@JvmInline
value class RenderColor(val packed: Int) {

    // ── 채널 접근 ─────────────────────────────────────────────────────────────

    /** Alpha 채널 (0–255) */
    val a: Int get() = (packed ushr 24) and 0xFF
    /** Red 채널 (0–255) */
    val r: Int get() = (packed ushr 16) and 0xFF
    /** Green 채널 (0–255) */
    val g: Int get() = (packed ushr 8) and 0xFF
    /** Blue 채널 (0–255) */
    val b: Int get() = packed and 0xFF

    /** Alpha (0.0–1.0) */
    val af: Float get() = a / 255f
    /** Red (0.0–1.0) */
    val rf: Float get() = r / 255f
    /** Green (0.0–1.0) */
    val gf: Float get() = g / 255f
    /** Blue (0.0–1.0) */
    val bf: Float get() = b / 255f

    // ── 변환 ─────────────────────────────────────────────────────────────────

    /** 알파값을 [alpha](0–255)로 교체한 새 RenderColor를 반환합니다. */
    fun withAlpha(alpha: Int): RenderColor = of(r, g, b, alpha)

    /** 알파값을 [alpha](0.0–1.0)로 교체한 새 RenderColor를 반환합니다. */
    fun withAlpha(alpha: Float): RenderColor = withAlpha((alpha * 255).toInt().coerceIn(0, 255))

    /** 밝기를 높인 새 색상을 반환합니다 (각 채널 × [factor]). */
    fun brighter(factor: Float = 1.4f): RenderColor = of(
        (r * factor).toInt().coerceAtMost(255),
        (g * factor).toInt().coerceAtMost(255),
        (b * factor).toInt().coerceAtMost(255),
        a
    )

    /** 어둡게 한 새 색상을 반환합니다 (각 채널 × [factor]). */
    fun darker(factor: Float = 0.7f): RenderColor = of(
        (r * factor).toInt().coerceAtLeast(0),
        (g * factor).toInt().coerceAtLeast(0),
        (b * factor).toInt().coerceAtLeast(0),
        a
    )

    override fun toString(): String = "RenderColor(#%08X)".format(packed)

    companion object {

        /** RGBA 채널로 [RenderColor]를 생성합니다. */
        fun of(r: Int, g: Int, b: Int, a: Int = 255): RenderColor = RenderColor(
            ((a and 0xFF) shl 24) or
            ((r and 0xFF) shl 16) or
            ((g and 0xFF) shl 8)  or
            (b  and 0xFF)
        )

        /** Float RGBA (0.0–1.0)로 생성합니다. */
        fun of(r: Float, g: Float, b: Float, a: Float = 1f): RenderColor = of(
            (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255),
            (b * 255).toInt().coerceIn(0, 255),
            (a * 255).toInt().coerceIn(0, 255)
        )

        /** HSB로 생성합니다 (hue 0.0–1.0). */
        fun fromHSB(hue: Float, saturation: Float, brightness: Float, alpha: Float = 1f): RenderColor {
            val rgb = java.awt.Color.HSBtoRGB(hue, saturation, brightness)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return of(r, g, b, (alpha * 255).toInt().coerceIn(0, 255))
        }

        /**
         * 마이그레이션 기간 한정 — java.awt.Color에서 변환합니다.
         * ECS/렌더 시스템으로 완전히 이식되면 이 메서드는 제거됩니다.
         */
        fun fromAwt(c: java.awt.Color): RenderColor = of(c.red, c.green, c.blue, c.alpha)

        // ── 상용 색상 상수 ──────────────────────────────────────────────────────

        val TRANSPARENT = of(0, 0, 0, 0)
        val BLACK       = of(0, 0, 0)
        val WHITE       = of(255, 255, 255)
        val RED         = of(255, 0, 0)
        val GREEN       = of(0, 255, 0)
        val BLUE        = of(0, 0, 255)
        val YELLOW      = of(255, 255, 0)
        val CYAN        = of(0, 255, 255)
        val MAGENTA     = of(255, 0, 255)
    }
}
