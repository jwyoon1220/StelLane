package io.github.jwyoon1220.app

import io.github.jwyoon1220.engine.GLFWWindow
import javax.swing.JFrame
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Swing 다이얼로그/팝업을 GLFW 창 환경에서 표시하기 위한 헬퍼.
 * popup.show()에 필요한 AWT Component를 임시 1×1 JFrame으로 대체합니다.
 */
object SwingHelper {

    /**
     * GLFW 커서 위치(스크린 좌표)에 [popup]을 표시합니다.
     * 반드시 게임 루프(메인) 스레드에서 호출 — 내부적으로 invokeLater를 사용합니다.
     */
    fun showPopup(popup: JPopupMenu, window: GLFWWindow) {
        val (scx, scy) = window.getScreenCursorPos()
        SwingUtilities.invokeLater {
            val tmp = JFrame()
            tmp.isUndecorated = true
            tmp.setLocation(scx, scy)
            tmp.setSize(1, 1)
            tmp.isVisible = true
            popup.addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuCanceled(e: PopupMenuEvent) = tmp.dispose()
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) = tmp.dispose()
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {}
            })
            popup.show(tmp, 0, 0)
        }
    }
}
