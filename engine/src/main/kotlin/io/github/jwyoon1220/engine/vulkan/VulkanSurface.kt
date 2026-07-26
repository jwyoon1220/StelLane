package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR
import org.lwjgl.vulkan.VkInstance

/** GLFW 창 핸들로부터 VkSurfaceKHR을 생성/해제합니다. 창은 [io.github.jwyoon1220.engine.RenderApi.VULKAN]으로 만들어야 합니다. */
object VulkanSurface {

    fun create(instance: VkInstance, windowHandle: Long): Long = stackPush().use { stack ->
        val pSurface = stack.mallocLong(1)
        vkCheck(
            GLFWVulkan.glfwCreateWindowSurface(instance, windowHandle, null, pSurface),
            "glfwCreateWindowSurface 실패"
        )
        pSurface[0]
    }

    fun destroy(instance: VkInstance, surface: Long) = vkDestroySurfaceKHR(instance, surface, null)
}
