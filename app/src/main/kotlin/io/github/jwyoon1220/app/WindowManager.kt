package io.github.jwyoon1220.app

import io.github.jwyoon1220.engine.GLFWWindow
import io.github.jwyoon1220.engine.WindowMode

/**
 * 창 모드 전환을 담당합니다.
 */
class WindowManager(val glfwWindow: GLFWWindow) {

    fun applyMode(mode: WindowMode) {
        glfwWindow.applyMode(mode)
        AppSettings.windowMode = mode
    }

    fun applyVSync(enabled: Boolean) {
        glfwWindow.setVSync(if (enabled) 1 else 0)
        AppSettings.vSync = enabled
    }
}
