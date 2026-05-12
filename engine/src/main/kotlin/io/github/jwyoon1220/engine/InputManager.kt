package io.github.jwyoon1220.engine

import it.unimi.dsi.fastutil.objects.ObjectArrayList
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

    private val laneKeyMap = mapOf(
        Keys.D to 0,
        Keys.F to 1,
        Keys.J to 2,
        Keys.K to 3
    )

    // 마우스 드래그용 버튼 추적
    private var pressedButton = -1

    init {
        window.onKey = { key, action, mods ->
            // 레인 키 → 큐
            val lane = laneKeyMap[key]
            if (lane != null) {
                when (action) {
                    Keys.PRESS   -> eventQueue.offer(LaneEvent(lane, LaneEventType.PRESS))
                    Keys.RELEASE -> eventQueue.offer(LaneEvent(lane, LaneEventType.RELEASE))
                }
            }
            // 모든 키 → State 콜백
            when (action) {
                Keys.PRESS, Keys.REPEAT -> stateKeyPressed?.invoke(key, mods)
                Keys.RELEASE            -> stateKeyReleased?.invoke(key, mods)
            }
        }

        window.onChar = { codepoint ->
            stateKeyTyped?.invoke(codepoint)
        }

        window.onMouseButton = { button, action, mods ->
            val (lx, ly) = renderer.toLogical(window.cursorX, window.cursorY)
            when (action) {
                Keys.PRESS -> {
                    pressedButton = button
                    stateMousePressed?.invoke(lx, ly, button, mods)
                }
                Keys.RELEASE -> {
                    stateMouseReleased?.invoke(lx, ly, button, mods)
                    stateMouseClicked?.invoke(lx, ly, button, mods)
                    pressedButton = -1
                }
            }
        }

        window.onCursorPos = { x, y ->
            if (pressedButton >= 0) {
                val (lx, ly) = renderer.toLogical(x, y)
                stateMouseDragged?.invoke(lx, ly, pressedButton)
            }
        }

        window.onScroll = { _, dy ->
            stateScroll?.invoke(dy)
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
}

