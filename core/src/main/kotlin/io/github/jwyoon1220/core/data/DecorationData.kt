package io.github.jwyoon1220.core.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * decoration.json 최상위 구조.
 * songs/<name>/decoration.json 에 배치하며, 파일이 없으면 장식 없이 동작.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DecorationData(
    val decorations: List<Decoration> = emptyList(),
    val screenEffects: List<ScreenEffect> = emptyList()
)

/**
 * 화면 위에 표시되는 하나의 장식 오브젝트.
 *
 * 좌표계: x/y 는 0.0(왼쪽/위)~1.0(오른쪽/아래) 정규화값.
 * 크기(width/height):
 *   - 0.0~1.0 → 1280 또는 720 의 비율 (정규화, 어떤 해상도에서도 동비율)
 *   - > 1.0   → 1280×720 기준 논리 픽셀 (하위 호환)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Decoration(
    /** 고유 식별자 (에디터/로그용) */
    val id: String = "",
    /** 곡 시작 기준 등장 시각 (ms) */
    val timeMs: Long = 0L,
    /** 표시 지속 시간 (ms) */
    val durationMs: Long = 1000L,
    /** songDir 기준 이미지 경로 */
    val image: String = "",
    /** 화면 X 위치 (0.0 = 왼쪽, 1.0 = 오른쪽) */
    val x: Float = 0.5f,
    /** 화면 Y 위치 (0.0 = 위, 1.0 = 아래) */
    val y: Float = 0.5f,
    /**
     * 너비: 0.0~1.0 이면 화면 너비의 비율 (e.g. 0.1 = 전체 폭의 10%).
     * 1.0 초과이면 1280×720 기준 논리 픽셀 (하위 호환).
     */
    val width: Float = 0.1f,
    /**
     * 높이: 0.0~1.0 이면 화면 높이의 비율.
     * 1.0 초과이면 720 기준 논리 픽셀 (하위 호환).
     */
    val height: Float = 0.1f,
    /** 회전/스케일 기준점 X (0.0 = 왼쪽, 1.0 = 오른쪽) */
    val pivotX: Float = 0.5f,
    /** 회전/스케일 기준점 Y (0.0 = 위, 1.0 = 아래) */
    val pivotY: Float = 0.5f,
    /** 초기 불투명도 (0.0~1.0) */
    val opacity: Float = 1.0f,
    /** 초기 회전각 (도, 시계 방향) */
    val rotation: Float = 0.0f,
    /** 초기 X 스케일 */
    val scaleX: Float = 1.0f,
    /** 초기 Y 스케일 */
    val scaleY: Float = 1.0f,
    /** 렌더링 순서 (낮을수록 뒤) */
    val depth: Int = 0,
    /** 이 장식에 적용되는 효과 목록 */
    val effects: List<DecEffect> = emptyList()
)

/**
 * 장식 오브젝트에 적용할 애니메이션 효과.
 *
 * type 값:
 *  - fadeIn      : 등장 시 opacity 0 → 초기값 페이드인
 *  - fadeOut     : opacity 초기값 → 0 페이드아웃
 *  - opacityTo   : opacity 를 toOpacity 로 애니메이션
 *  - moveTo      : 위치를 toX/toY 로 애니메이션 (0.0~1.0 정규화)
 *  - rotateTo    : rotation 을 toRotation 도로 애니메이션
 *  - scaleTo     : 스케일을 toScaleX/toScaleY 로 애니메이션
 *  - colorFilter : 이미지에 RGBA 틴트 오버레이
 *  - shake       : amplitude/frequency 로 진동
 *
 * 효과가 완료(elapsedMs >= startMs+durationMs)된 후에는 최종값(t=1)을 유지(래치).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DecEffect(
    val type: String = "",
    /** 장식 timeMs 기준 시작 오프셋 (ms) */
    val startMs: Long = 0L,
    /** 효과 지속 시간 (ms), 0이면 즉시 적용 */
    val durationMs: Long = 500L,
    /** 이징: linear | easeIn | easeOut | easeInOut */
    val easing: String = "linear",

    // moveTo
    val toX: Float? = null,
    val toY: Float? = null,
    // rotateTo
    val toRotation: Float? = null,
    // scaleTo
    val toScaleX: Float? = null,
    val toScaleY: Float? = null,
    // opacityTo
    val toOpacity: Float? = null,

    // colorFilter (RGBA 틴트)
    val r: Int = 255,
    val g: Int = 255,
    val b: Int = 255,
    val a: Int = 128,

    // shake
    val amplitude: Float = 5f,
    val frequency: Float = 10f
)

/**
 * 화면 전체에 적용되는 효과.
 *
 * type 값:
 *  - flash        : 짧은 색상 플래시 (페이드 인→아웃)
 *  - colorOverlay : 반투명 색상 레이어
 *  - vignette     : 가장자리 어둡게 (intensity = 0.0~1.0)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenEffect(
    val timeMs: Long = 0L,
    val durationMs: Long = 200L,
    val type: String = "",
    val r: Int = 255,
    val g: Int = 255,
    val b: Int = 255,
    val a: Int = 200,
    /** vignette 강도 (0.0~1.0) */
    val intensity: Float = 0.5f,
    /** 이징: linear | easeIn | easeOut | easeInOut */
    val easing: String = "linear"
)
