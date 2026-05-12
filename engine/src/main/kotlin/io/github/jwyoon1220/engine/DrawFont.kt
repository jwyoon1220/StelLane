package io.github.jwyoon1220.engine

/**
 * NanoVG 폰트 핸들 + 크기를 묶은 불변 값 객체.
 * java.awt.Font 를 대체하며, deriveFont() 로 크기만 바꾼 복사본을 생성할 수 있습니다.
 */
data class DrawFont(
    /** nvgCreateFontMem/nvgCreateFont 에서 반환된 폰트 핸들 */
    val id: Int,
    val size: Float
) {
    fun deriveFont(newSize: Float): DrawFont = copy(size = newSize)
    fun deriveFont(style: Int, newSize: Float): DrawFont = copy(size = newSize)
    /** java.awt.Font.size2D 호환 */
    val size2D: Float get() = size
}
