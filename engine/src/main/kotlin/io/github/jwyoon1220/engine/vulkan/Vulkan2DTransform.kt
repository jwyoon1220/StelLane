package io.github.jwyoon1220.engine.vulkan

import kotlin.math.cos
import kotlin.math.sin

/**
 * 2D 아핀 변환 (3x3의 마지막 행 [0,0,1] 생략) — `[a c tx; b d ty]`.
 * 점 변환: `x' = a*x + c*y + tx`, `y' = b*x + d*y + ty`.
 *
 * NanoVG/Graphics2D와 동일한 합성 규칙을 씁니다: [translate]/[scale]/[rotate]는 항상 "지금 그리는
 * 로컬 좌표계 기준"으로 적용됩니다 — 즉 `save(); translate(10,0); scale(2,2); ...그리기...; restore()`처럼
 * 호출 순서대로 로컬 변환이 누적됩니다(M_new = M_old ∘ M_delta).
 */
class Mat3(
    val a: Float = 1f, val b: Float = 0f,
    val c: Float = 0f, val d: Float = 1f,
    val tx: Float = 0f, val ty: Float = 0f
) {
    companion object { val IDENTITY = Mat3() }

    fun transformX(x: Float, y: Float) = a * x + c * y + tx
    fun transformY(x: Float, y: Float) = b * x + d * y + ty

    /** this ∘ delta — delta를 로컬 좌표계에 먼저 적용한 뒤 this를 적용하는 합성. */
    fun compose(delta: Mat3): Mat3 = Mat3(
        a = a * delta.a + c * delta.b,
        b = b * delta.a + d * delta.b,
        c = a * delta.c + c * delta.d,
        d = b * delta.c + d * delta.d,
        tx = a * delta.tx + c * delta.ty + tx,
        ty = b * delta.tx + d * delta.ty + ty
    )
}

/** [DrawContext]의 save/restore/translate/scale/rotate를 구현하는 CPU 측 변환 스택. */
class TransformStack {
    var current: Mat3 = Mat3.IDENTITY
        private set
    private val stack = ArrayDeque<Mat3>()

    fun reset() { current = Mat3.IDENTITY; stack.clear() }
    fun save() = stack.addLast(current)
    fun restore() { current = stack.removeLastOrNull() ?: Mat3.IDENTITY }

    fun translate(tx: Float, ty: Float) { current = current.compose(Mat3(tx = tx, ty = ty)) }
    fun scale(sx: Float, sy: Float) { current = current.compose(Mat3(a = sx, d = sy)) }
    /** theta는 라디안. */
    fun rotate(theta: Float) {
        val cs = cos(theta); val sn = sin(theta)
        current = current.compose(Mat3(a = cs, b = sn, c = -sn, d = cs))
    }
}
