package io.github.jwyoon1220.engine

import java.io.File

/**
 * 한 프레임에 적용할 GL 후처리 효과의 스냅샷.
 * [DecorationRenderer.collectGlEffects]가 생성해 [PostProcessPass]가 소비합니다.
 *
 * type:
 *  - "grayscale" : 흑백 (intensity 0~1)
 *  - "fade"      : 색상으로 페이드 (r/g/b/a = 대상 색, intensity = 혼합 비율)
 *  - "blur"      : 박스 블러 (intensity 0~1)
 *  - "shader"    : 커스텀 GLSL 프래그먼트 (shaderFile 필수)
 */
data class GlScreenEffectData(
    val type: String,
    val intensity: Float,
    val r: Float = 0f,
    val g: Float = 0f,
    val b: Float = 0f,
    val a: Float = 1f,
    val shaderFile: File? = null
)

/** 씬이 활성화된 GL 후처리 효과 목록을 렌더러에 노출합니다. */
interface GlEffectProvider {
    fun collectActiveGlEffects(): List<GlScreenEffectData>
}
