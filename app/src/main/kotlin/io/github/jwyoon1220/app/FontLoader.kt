package io.github.jwyoon1220.app

import io.github.jwyoon1220.engine.DrawFont
import io.github.jwyoon1220.engine.FontRegistry

/**
 * FontRegistry 에 대한 앱 레벨 편의 래퍼.
 * 기존 코드가 FontLoader.regular(size), FontLoader.bold 등을 그대로 사용할 수 있도록 API 를 유지합니다.
 */
object FontLoader {

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
}
