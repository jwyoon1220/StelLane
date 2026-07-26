package io.github.jwyoon1220.engine.vulkan

import io.github.jwyoon1220.engine.GLFWWindow
import io.github.jwyoon1220.engine.RenderApi
import io.github.jwyoon1220.engine.WindowMode
import io.github.jwyoon1220.engine.render.RendererContext
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwPollEvents
import org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported

/**
 * Vulkan 부트스트랩 스모크 테스트.
 *
 * [VideoBackgroundTest]와 같은 패턴 — 이 머신에 Vulkan을 지원하는 GPU/드라이버가 없으면
 * (헤드리스 CI 등) [assumeTrue]로 조용히 skip합니다.
 *
 * 검증 항목: Vulkan 모드 창 생성 → VulkanContext 부트스트랩(instance~sync objects) →
 * 클리어 컬러 프레임 여러 번 프레젠트 → 리사이즈 시뮬레이션(스왑체인 재생성) → 클린 종료.
 * 검증 레이어를 켜두므로(enableValidation=true) 여기서 콘솔에 [VulkanValidation] 에러/워닝이
 * 찍히지 않는지도 눈으로 함께 확인하세요.
 */
class VulkanContextSmokeTest {

    @Test
    fun `bootstraps device and presents cleared frames`() {
        check(glfwInit()) { "GLFW 초기화 실패" }
        assumeTrue(glfwVulkanSupported(), "이 환경은 Vulkan을 지원하지 않습니다 — 스모크 테스트를 건너뜁니다")

        val window = GLFWWindow.createWindow(
            title  = "StelLane Vulkan Smoke Test",
            width  = 320,
            height = 240,
            mode   = WindowMode.WINDOWED,
            api    = RenderApi.VULKAN
        )

        try {
            val backend = VulkanBackend(enableValidation = true)
            backend.init(RendererContext(window = window))

            repeat(5) {
                glfwPollEvents()
                backend.beginFrame(window.framebufferWidth, window.framebufferHeight, 1f, 0f, 0f)
                backend.submit(emptyList())
                backend.endFrame()
            }

            // 리사이즈 시뮬레이션 — out-of-date 없이도 명시적 재생성 경로가 안전한지 확인
            backend.notifyResize(window.framebufferWidth / 2, window.framebufferHeight / 2)
            repeat(3) {
                glfwPollEvents()
                backend.beginFrame(window.framebufferWidth / 2, window.framebufferHeight / 2, 1f, 0f, 0f)
                backend.submit(emptyList())
                backend.endFrame()
            }

            backend.destroy()
        } finally {
            window.destroy()
        }
    }
}
