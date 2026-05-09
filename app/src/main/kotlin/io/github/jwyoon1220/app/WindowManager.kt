package io.github.jwyoon1220.app

import java.awt.Component
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * 창 모드 전환을 담당합니다.
 * 데코레이션 변경은 JFrame.dispose() 후 재표시가 필요합니다.
 */
class WindowManager(val frame: JFrame, private val focusTarget: Component? = null) {

    fun applyMode(mode: WindowMode) {
        SwingUtilities.invokeLater {
            val gd = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice

            // 독점 전체화면 해제 (다른 모드로 전환 시 먼저)
            if (gd.fullScreenWindow == frame) gd.fullScreenWindow = null

            when (mode) {
                WindowMode.WINDOWED -> {
                    frame.dispose()
                    frame.isUndecorated = false
                    frame.extendedState = JFrame.NORMAL
                    frame.setSize(1280, 720)
                    frame.setLocationRelativeTo(null)
                    frame.isVisible = true
                }
                WindowMode.BORDERLESS -> {
                    frame.dispose()
                    frame.isUndecorated = true
                    frame.extendedState = JFrame.MAXIMIZED_BOTH
                    frame.isVisible = true
                }
                WindowMode.EXCLUSIVE -> {
                    if (!gd.isFullScreenSupported) {
                        applyMode(WindowMode.BORDERLESS)
                        return@invokeLater
                    }
                    frame.dispose()
                    frame.isUndecorated = true
                    frame.extendedState = JFrame.NORMAL
                    frame.isVisible = true
                    gd.fullScreenWindow = frame
                }
            }

            (focusTarget ?: frame).requestFocusInWindow()
            AppSettings.windowMode = mode
        }
    }
}
