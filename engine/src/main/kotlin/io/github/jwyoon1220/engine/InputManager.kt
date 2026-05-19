package io.github.jwyoon1220.engine

import it.unimi.dsi.fastutil.objects.ObjectArrayList
import imgui.ImGui
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.KeyInputEvent
import io.github.jwyoon1220.engine.ecs.LaneInputEvent
import io.github.jwyoon1220.engine.ecs.MouseInputEvent
import org.jctools.queues.MpscArrayQueue

enum class LaneEventType { PRESS, RELEASE }

data class LaneEvent(val lane: Int, val type: LaneEventType)

/**
 * GLFW 키/마우스 입력을 라우팅합니다.
 *
 * - D/F/J/K (레인 0–3) 입력은 [MpscArrayQueue] 로 수집해 update() 에서 드레인합니다.
 * - 나머지 키·마우스 이벤트는 [stateKeyPressed] 등의 콜백을 통해 현재 State 에 전달됩니다.
 * - [Renderer.toLogical] 을 통해 물리 픽셀 → 논리(1280×720) 좌표로 변환합니다.
 */
class InputManager(
    private val window: GLFWWindow,
    private val renderer: Renderer
) {
    // ── 레인 이벤트 큐 (MPSC: GLFW 콜백 → 메인 스레드 update) ───────────────
    private val eventQueue = MpscArrayQueue<LaneEvent>(1024)

    // ── State 라우팅 콜백 (Main 에서 설정) ───────────────────────────────────
    var stateKeyPressed : ((key: Int, mods: Int) -> Unit)? = null
    var stateKeyReleased: ((key: Int, mods: Int) -> Unit)? = null
    var stateKeyTyped   : ((codepoint: Int)      -> Unit)? = null
    var stateMousePressed : ((x: Float, y: Float, button: Int, mods: Int) -> Unit)? = null
    var stateMouseReleased: ((x: Float, y: Float, button: Int, mods: Int) -> Unit)? = null
    var stateMouseClicked : ((x: Float, y: Float, button: Int, mods: Int) -> Unit)? = null
    var stateMouseDragged : ((x: Float, y: Float, button: Int)            -> Unit)? = null
    var stateScroll       : ((dy: Double)                                 -> Unit)? = null

    /** ImGuiManager 가 주입되면 wantCapture 플래그를 체크해 입력을 필터링합니다. */
    var imGuiManager: ImGuiManager? = null

    private val laneKeyMap = mapOf(
        Keys.D to 0,
        Keys.F to 1,
        Keys.J to 2,
        Keys.K to 3
    )

    // 마우스 드래그용 버튼 추적
    private var pressedButton = -1

    // ── ECS InputSnapshot 수집 버퍼 ─────────────────────────────────────────
    private val pendingKeyEvents   = ObjectArrayList<KeyInputEvent>(16)
    private val pendingMouseEvents = ObjectArrayList<MouseInputEvent>(8)
    @Volatile private var frameScrollDy = 0.0
    @Volatile private var logicalX = 0f
    @Volatile private var logicalY = 0f

    init {
        window.onKey = { key, action, mods ->
            // 레인 키 → 큐 (ImGui wantCaptureKeyboard 와 무관하게 항상 큐에 넣음)
            val lane = laneKeyMap[key]
            if (lane != null) {
                when (action) {
                    Keys.PRESS   -> eventQueue.offer(LaneEvent(lane, LaneEventType.PRESS))
                    Keys.RELEASE -> eventQueue.offer(LaneEvent(lane, LaneEventType.RELEASE))
                }
            }
            // 일반 키 → ImGui 텍스트 입력 중이 아닐 때만 State 에 전달
            val imguiCaptures = imGuiManager != null && ImGui.getIO().wantTextInput
            if (!imguiCaptures) {
                when (action) {
                    Keys.PRESS, Keys.REPEAT -> stateKeyPressed?.invoke(key, mods)
                    Keys.RELEASE            -> stateKeyReleased?.invoke(key, mods)
                }
            }
            // ECS 경로: 항상 수집 (ImGui 필터 무관)
            pendingKeyEvents.add(KeyInputEvent(key, action, mods))
        }

        window.onChar = { codepoint ->
            val imguiCaptures = imGuiManager != null && ImGui.getIO().wantTextInput
            if (!imguiCaptures) stateKeyTyped?.invoke(codepoint)
        }

        window.onMouseButton = { button, action, mods ->
            val imguiCaptures = imGuiManager != null && ImGui.getIO().wantCaptureMouse
            val (lx, ly) = renderer.toLogical(window.cursorX, window.cursorY)
            logicalX = lx; logicalY = ly
            when (action) {
                Keys.PRESS -> {
                    pressedButton = button
                    stateMousePressed?.invoke(lx, ly, button, mods)
                    pendingMouseEvents.add(MouseInputEvent(lx, ly, button, Keys.PRESS, mods))
                }
                Keys.RELEASE -> {
                    // pressedButton >= 0: 이전에 press가 있었을 때만 콜백 실행
                    if (pressedButton >= 0) {
                        stateMouseReleased?.invoke(lx, ly, button, mods)
                        stateMouseClicked?.invoke(lx, ly, button, mods)
                        pressedButton = -1
                        pendingMouseEvents.add(MouseInputEvent(lx, ly, button, Keys.RELEASE, mods))
                    }
                }
            }
        }

        window.onCursorPos = { x, y ->
            if (pressedButton >= 0) {
                val (lx, ly) = renderer.toLogical(x, y)
                logicalX = lx; logicalY = ly
                stateMouseDragged?.invoke(lx, ly, pressedButton)
            } else {
                val (lx, ly) = renderer.toLogical(x, y)
                logicalX = lx; logicalY = ly
            }
        }

        window.onScroll = { _, dy ->
            stateScroll?.invoke(dy)
            frameScrollDy += dy
        }
    }

    private val pollBuffer = ObjectArrayList<LaneEvent>()

    /** GameLoop update() 에서 호출 — 레인 이벤트를 드레인합니다. */
    fun pollEvents(): List<LaneEvent> {
        pollBuffer.clear()
        while (true) pollBuffer.add(eventQueue.poll() ?: break)
        return pollBuffer
    }

    /** 상태 전환 시 잔여 레인 이벤트를 비웁니다. */
    fun clearEvents() = eventQueue.clear()

    /**
     * 이번 프레임의 [InputSnapshot]을 빌드합니다.
     *
     * 레인 큐를 드레인하고 누적된 키/마우스/스크롤 이벤트를 수집합니다.
     * [GameLoop]가 [window.pollEvents] 직후 호출합니다.
     *
     * @param frameTimeNs 프레임 시작 시각 (System.nanoTime())
     */
    fun buildSnapshot(frameTimeNs: Long = System.nanoTime()): InputSnapshot {
        val laneEvents = mutableListOf<LaneInputEvent>()
        while (true) {
            val e = eventQueue.poll() ?: break
            laneEvents.add(LaneInputEvent(e.lane, e.type == LaneEventType.PRESS))
        }
        val snap = InputSnapshot(
            laneEvents   = laneEvents,
            keyEvents    = pendingKeyEvents.toList(),
            mouseEvents  = pendingMouseEvents.toList(),
            cursorX      = logicalX,
            cursorY      = logicalY,
            scrollDy     = frameScrollDy,
            frameTimeNs  = frameTimeNs
        )
        pendingKeyEvents.clear()
        pendingMouseEvents.clear()
        frameScrollDy = 0.0
        return snap
    }
}

