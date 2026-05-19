package io.github.jwyoon1220.engine.ecs

/**
 * 한 프레임 동안의 입력 상태를 담은 불변 스냅샷.
 *
 * GameLoop가 매 프레임 생성해 모든 EcsSystem에 전달합니다.
 * GLFW 콜백 → InputManager → InputSnapshot 순서로 변환됩니다.
 */
data class InputSnapshot(
    /** 레인(0–3) 이벤트 목록 — PRESS/RELEASE 순서 보장 */
    val laneEvents: List<LaneInputEvent> = emptyList(),
    /** 일반 키 이벤트 목록 (레인 키 포함) */
    val keyEvents: List<KeyInputEvent> = emptyList(),
    /** 마우스 버튼 이벤트 목록 */
    val mouseEvents: List<MouseInputEvent> = emptyList(),
    /** 논리 좌표(1280×720 기준) 현재 커서 X */
    val cursorX: Float = 0f,
    /** 논리 좌표(1280×720 기준) 현재 커서 Y */
    val cursorY: Float = 0f,
    /** 이번 프레임 스크롤 누적 Y (-= 아래, += 위) */
    val scrollDy: Double = 0.0,
    /** 프레임 시작 시각 (System.nanoTime()) — 판정 타이밍에 사용 */
    val frameTimeNs: Long = 0L
) {
    companion object {
        val EMPTY = InputSnapshot()
    }
}

// ── 레인 입력 ─────────────────────────────────────────────────────────────────

data class LaneInputEvent(
    /** 레인 번호 (0 = D, 1 = F, 2 = J, 3 = K 기본값) */
    val lane: Int,
    /** true = PRESS, false = RELEASE */
    val pressed: Boolean
)

// ── 키보드 입력 ───────────────────────────────────────────────────────────────

data class KeyInputEvent(
    /** GLFW_KEY_* 상수 */
    val key: Int,
    /** Keys.PRESS / Keys.RELEASE / Keys.REPEAT */
    val action: Int,
    /** GLFW modifier bits (GLFW_MOD_SHIFT 등) */
    val mods: Int
)

// ── 마우스 입력 ───────────────────────────────────────────────────────────────

data class MouseInputEvent(
    /** 논리 좌표 X (1280×720 기준) */
    val x: Float,
    /** 논리 좌표 Y (1280×720 기준) */
    val y: Float,
    /** GLFW_MOUSE_BUTTON_* 상수 */
    val button: Int,
    /** Keys.PRESS / Keys.RELEASE */
    val action: Int,
    /** GLFW modifier bits */
    val mods: Int
)
