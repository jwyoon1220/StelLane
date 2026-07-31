package io.github.jwyoon1220.app

import io.github.jwyoon1220.engine.GLFWWindow
import io.github.jwyoon1220.engine.Renderer
import io.github.jwyoon1220.engine.WindowMode

/**
 * 창 모드 전환을 담당합니다.
 */
class WindowManager(val glfwWindow: GLFWWindow, private val renderer: Renderer) {

    fun applyMode(mode: WindowMode) {
        glfwWindow.applyMode(mode)
        AppSettings.windowMode = mode
    }

    /**
     * glfwWindow.setVSync는 OpenGL 모드에서만 실제로 적용됩니다(Vulkan은 GL 컨텍스트가 없음) —
     * Vulkan은 renderer.setVSync가 스왑체인을 다시 만들어 적용합니다. 둘 다 항상 호출해도
     * 안전합니다(현재 백엔드와 무관한 쪽은 아무 일도 안 함).
     */
    fun applyVSync(enabled: Boolean) {
        glfwWindow.setVSync(if (enabled) 1 else 0)
        renderer.setVSync(enabled)
        AppSettings.vSync = enabled
    }
}
