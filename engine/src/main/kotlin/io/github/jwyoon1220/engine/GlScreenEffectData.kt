package io.github.jwyoon1220.engine

import java.io.File

/**
 * 한 프레임에 적용할 GL 후처리 효과의 스냅샷.
 * [DecorationRenderer.collectGlEffects]가 생성해 [PostProcessPass]가 소비합니다.
 *
 * type:
 *  - "grayscale"   : 흑백 (intensity 0~1)
 *  - "fade"        : 색상으로 페이드 (r/g/b/a = 대상 색, intensity = 혼합 비율)
 *  - "blur"        : 박스 블러 (intensity 0~1)
 *  - "crt"         : CRT 스캔라인 + 색수차 + 비네팅
 *  - "bloom"       : 밝은 영역 빛번짐 (intensity = 강도)
 *  - "pixelate"    : 픽셀화 (intensity 0→고해상도, 1→극저해상도)
 *  - "chromab"     : 색수차, 가장자리로 갈수록 RGB 분리
 *  - "sepia"       : 세피아 빈티지 톤
 *  - "invert"      : 색상 반전
 *  - "vignette_gl" : GL 비네팅 (r/g/b/a = 가장자리 색, 보통 검정)
 *  - "filmgrain"   : 필름 그레인 노이즈 (시간 기반 애니메이션)
 *  - "glitch"      : 수평 블록 글리치 + 색수차 (시간 기반 애니메이션)
 *  - "shader"      : 커스텀 GLSL 프래그먼트 (shaderFile 필수)
 *                    유니폼: uTex(sampler2D), uIntensity(float), uTime(float),
 *                            uTexelSize(vec2), uColor(vec4)
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
