package io.github.jwyoon1220.app

import io.github.jwyoon1220.engine.DrawFont
import io.github.jwyoon1220.engine.FontRegistry

/**
 * FontRegistry 에 대한 앱 레벨 편의 래퍼.
 * 기존 코드가 FontLoader.regular(size), FontLoader.bold 등을 그대로 사용할 수 있도록 API 를 유지합니다.
 */
object FontLoader {

    // ── MaruBuri (한국어/기본 폰트) ─────────────────────────────────────────
    val regular:    DrawFont get() = FontRegistry.regular
    val bold:       DrawFont get() = FontRegistry.bold
    val semiBold:   DrawFont get() = FontRegistry.semiBold
    val light:      DrawFont get() = FontRegistry.light
    val extraLight: DrawFont get() = FontRegistry.extraLight

    fun regular   (size: Float): DrawFont = FontRegistry.regular(size)
    fun bold      (size: Float): DrawFont = FontRegistry.bold(size)
    fun semiBold  (size: Float): DrawFont = FontRegistry.semiBold(size)
    fun light     (size: Float): DrawFont = FontRegistry.light(size)
    fun extraLight(size: Float): DrawFont = FontRegistry.extraLight(size)

    // ── Pretendard (SIL OFL — 한국어/영문 현대 고딕) ────────────────────────
    val pretendardRegular:    DrawFont get() = FontRegistry.pretendardRegular
    val pretendardBold:       DrawFont get() = FontRegistry.pretendardBold
    val pretendardSemiBold:   DrawFont get() = FontRegistry.pretendardSemiBold
    val pretendardLight:      DrawFont get() = FontRegistry.pretendardLight
    val pretendardExtraLight: DrawFont get() = FontRegistry.pretendardExtraLight

    fun pretendardRegular   (size: Float): DrawFont = FontRegistry.pretendardRegular(size)
    fun pretendardBold      (size: Float): DrawFont = FontRegistry.pretendardBold(size)
    fun pretendardSemiBold  (size: Float): DrawFont = FontRegistry.pretendardSemiBold(size)
    fun pretendardLight     (size: Float): DrawFont = FontRegistry.pretendardLight(size)
    fun pretendardExtraLight(size: Float): DrawFont = FontRegistry.pretendardExtraLight(size)

    // ── Inter (OFL — Latin/숫자 UI 전용) ────────────────────────────────────
    val interRegular:    DrawFont get() = FontRegistry.interRegular
    val interBold:       DrawFont get() = FontRegistry.interBold
    val interSemiBold:   DrawFont get() = FontRegistry.interSemiBold
    val interMedium:     DrawFont get() = FontRegistry.interMedium
    val interLight:      DrawFont get() = FontRegistry.interLight
    val interExtraLight: DrawFont get() = FontRegistry.interExtraLight

    fun interRegular   (size: Float): DrawFont = FontRegistry.interRegular(size)
    fun interBold      (size: Float): DrawFont = FontRegistry.interBold(size)
    fun interSemiBold  (size: Float): DrawFont = FontRegistry.interSemiBold(size)
    fun interMedium    (size: Float): DrawFont = FontRegistry.interMedium(size)
    fun interLight     (size: Float): DrawFont = FontRegistry.interLight(size)
    fun interExtraLight(size: Float): DrawFont = FontRegistry.interExtraLight(size)
}
