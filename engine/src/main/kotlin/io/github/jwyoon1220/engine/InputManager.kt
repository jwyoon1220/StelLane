package io.github.jwyoon1220.engine

import it.unimi.dsi.fastutil.objects.ObjectArrayList
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import org.jctools.queues.MpscArrayQueue
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

enum class LaneEventType { PRESS, RELEASE }

data class LaneEvent(val lane: Int, val type: LaneEventType)

/**
 * D/F/J/K (레인 0–3) 키 입력을 JCTools의 [MpscArrayQueue]로 수집합니다.
 * EDT(키 이벤트 스레드)에서 큐에 넣고, GameLoopThread의 update()에서 드레인합니다.
 * 따라서 fastutil 컬렉션은 GameLoopThread 단일 스레드에서만 접근합니다.
 */
class InputManager(private val component: JComponent) {

    // 멀티 프로듀서(EDT/기타 스레드) - 싱글 컨슈머(GameLoopThread) 환경에 최적화된 초고속 큐
    private val eventQueue = MpscArrayQueue<LaneEvent>(1024)

    private val laneKeys = mapOf(
        0 to KeyEvent.VK_D,
        1 to KeyEvent.VK_F,
        2 to KeyEvent.VK_J,
        3 to KeyEvent.VK_K
    )

    init {
        laneKeys.forEach { (lane, vk) ->
            val pressAction   = "lane_press_$lane"
            val releaseAction = "lane_release_$lane"

            component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(vk, 0, false), pressAction)
            component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(vk, 0, true),  releaseAction)

            component.actionMap.put(pressAction, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    eventQueue.offer(LaneEvent(lane, LaneEventType.PRESS))
                }
            })
            component.actionMap.put(releaseAction, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    eventQueue.offer(LaneEvent(lane, LaneEventType.RELEASE))
                }
            })
        }
    }

    private val pollBuffer = ObjectArrayList<LaneEvent>()

    /** GameLoopThread에서 호출 — 큐에 쌓인 이벤트를 전부 드레인합니다. 반환된 리스트는 다음 pollEvents 호출 시 초기화됩니다. */
    fun pollEvents(): List<LaneEvent> {
        pollBuffer.clear()
        while (true) pollBuffer.add(eventQueue.poll() ?: break)
        return pollBuffer
    }

    /** 상태 전환 시 잔여 이벤트를 비웁니다. */
    fun clearEvents() = eventQueue.clear()

    /** InputMap/ActionMap 항목을 제거합니다 (필요 시 호출). */
    fun unregister() {
        laneKeys.forEach { (lane, vk) ->
            component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .remove(KeyStroke.getKeyStroke(vk, 0, false))
            component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .remove(KeyStroke.getKeyStroke(vk, 0, true))
            component.actionMap.remove("lane_press_$lane")
            component.actionMap.remove("lane_release_$lane")
        }
        eventQueue.clear()
    }
}
