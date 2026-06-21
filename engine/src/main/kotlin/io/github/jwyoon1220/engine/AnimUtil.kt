package io.github.jwyoon1220.engine

/** 애니메이션 보조 함수 — 모든 모듈에서 공유합니다. */
object AnimUtil {
    fun ease(t: Float, easing: String): Float = when (easing) {
        "easeIn"    -> t * t
        "easeOut"   -> 1f - (1f - t) * (1f - t)
        "easeInOut" -> if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f
        else        -> t
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
