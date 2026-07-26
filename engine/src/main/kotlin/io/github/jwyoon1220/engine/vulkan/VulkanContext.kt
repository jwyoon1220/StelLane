package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkClearValue
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkPresentInfoKHR
import org.lwjgl.vulkan.VkRenderPassBeginInfo
import org.lwjgl.vulkan.VkSubmitInfo
import org.slf4j.LoggerFactory

/** vkWaitForFences/vkAcquireNextImageKHR의 "무한 대기" 타임아웃 — 부호 없는 64비트 최댓값의 비트 패턴. */
private const val UINT64_MAX = -1L

/**
 * Vulkan 부트스트랩 오케스트레이터 — instance/surface/device/swapchain/renderpass/framebuffers/
 * command pool/sync object를 전부 소유하고 "클리어 컬러로 화면 채우고 프레젠트"까지의 프레임 루프를 담당합니다.
 *
 * 실제 2D 지오메트리(RenderCommand)를 그리는 파이프라인은 아직 없습니다 — [recordCommandBuffer]의
 * 렌더패스 begin/end 사이가 그 다음 단계가 들어갈 자리입니다. 지금은 검증 레이어가 깨끗한 상태로
 * 스왑체인 프레젠트 루프가 안정적으로 도는 것 자체가 목표(기반)입니다.
 *
 * 스레드 규칙: [GLFWWindow]와 마찬가지로 GLFW 메인 스레드에서만 호출하세요.
 */
class VulkanContext private constructor(
    private val windowHandle: Long,
    private val instance: VulkanInstance,
    private val surface: Long,
    private val physical: PickedPhysicalDevice,
    private val device: VulkanDevice,
    private var swapchain: VulkanSwapchain,
    private val renderPass: Long,
    private var framebuffers: LongArray,
    private val commandPool: Long,
    private val commandBuffers: List<VkCommandBuffer>,
    private val syncs: List<FrameSync>,
    private var renderFinishedSemaphores: LongArray
) {
    companion object {
        private val log = LoggerFactory.getLogger(VulkanContext::class.java)
        const val MAX_FRAMES_IN_FLIGHT = 2

        /**
         * @param windowHandle GLFW 창 핸들 — [io.github.jwyoon1220.engine.RenderApi.VULKAN]으로 생성된 창이어야 함
         * @param enableValidation 검증 레이어 활성화 여부 (Vulkan SDK 미설치 시 자동으로 무시됨)
         */
        fun create(
            appName: String,
            windowHandle: Long,
            fbWidth: Int,
            fbHeight: Int,
            enableValidation: Boolean = true
        ): VulkanContext {
            val instance = VulkanInstance.create(appName, enableValidation)
            val surface = VulkanSurface.create(instance.handle, windowHandle)
            val physical = VulkanPhysicalDevice.pick(instance.handle, surface)
            val device = VulkanDevice.create(physical)
            val swapchain = VulkanSwapchain.create(physical, device, surface, fbWidth, fbHeight)
            val renderPass = VulkanRenderPass.create(device.handle, swapchain.format)
            val framebuffers = VulkanFramebuffers.create(device.handle, renderPass, swapchain.imageViews, swapchain.extentW, swapchain.extentH)
            val commandPool = VulkanCommandPool.create(device.handle, physical.queueFamilies.graphicsFamily!!)
            val commandBuffers = VulkanCommandPool.allocateBuffers(device.handle, commandPool, MAX_FRAMES_IN_FLIGHT)
            val syncs = VulkanSyncObjects.create(device.handle, MAX_FRAMES_IN_FLIGHT)
            // renderFinished는 프레임이 아니라 "스왑체인 이미지"당 하나 — 자세한 이유는 FrameSync 문서 참고.
            val renderFinished = VulkanSyncObjects.createSemaphores(device.handle, swapchain.images.size)

            log.info("[Vulkan] 부트스트랩 완료 — {}×{}, {}개 스왑체인 이미지", swapchain.extentW, swapchain.extentH, swapchain.images.size)
            return VulkanContext(windowHandle, instance, surface, physical, device, swapchain, renderPass, framebuffers, commandPool, commandBuffers, syncs, renderFinished)
        }
    }

    /** RGB 클리어 컬러 (0f~1f). 기본값은 엔진 배경색과 맞춘 진보라. */
    var clearColor: FloatArray = floatArrayOf(10f / 255f, 8f / 255f, 20f / 255f)

    private var currentFrame = 0

    /**
     * 한 프레임을 그리고 프레젠트합니다. [fbWidth]/[fbHeight]는 호출측(창 리사이즈 콜백 등)이
     * 최신 프레임버퍼 크기를 넘겨줘야 하며, out-of-date/suboptimal이면 자동으로 스왑체인을 재생성합니다.
     */
    fun drawFrame(fbWidth: Int, fbHeight: Int) {
        if (fbWidth <= 0 || fbHeight <= 0) return // 최소화된 창 — 그릴 것 없음

        val sync = syncs[currentFrame]
        vkWaitForFences(device.handle, sync.inFlightFence, true, UINT64_MAX)

        val imageIndex = stackPush().use { stack ->
            val pImageIndex = stack.mallocInt(1)
            val result = vkAcquireNextImageKHR(device.handle, swapchain.handle, UINT64_MAX, sync.imageAvailable, VK_NULL_HANDLE, pImageIndex)
            if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapchain(fbWidth, fbHeight)
                return
            }
            check(result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR) { "vkAcquireNextImageKHR 실패 (VkResult=$result)" }
            pImageIndex[0]
        }

        vkResetFences(device.handle, sync.inFlightFence)

        val cmdBuf = commandBuffers[currentFrame]
        recordCommandBuffer(cmdBuf, imageIndex)
        submit(cmdBuf, sync, imageIndex)

        val presentResult = present(imageIndex)
        if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR) {
            recreateSwapchain(fbWidth, fbHeight)
        } else {
            vkCheck(presentResult, "vkQueuePresentKHR 실패")
        }

        currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT
    }

    private fun recordCommandBuffer(cmdBuf: VkCommandBuffer, imageIndex: Int) = stackPush().use { stack ->
        vkCheck(vkResetCommandBuffer(cmdBuf, 0), "vkResetCommandBuffer 실패")

        val beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
        vkCheck(vkBeginCommandBuffer(cmdBuf, beginInfo), "vkBeginCommandBuffer 실패")

        val clearValues = VkClearValue.calloc(1, stack)
        clearValues[0].color().float32(0, clearColor[0]).float32(1, clearColor[1]).float32(2, clearColor[2]).float32(3, 1f)

        val renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
            .renderPass(renderPass)
            .framebuffer(framebuffers[imageIndex])
            .pClearValues(clearValues)
        renderPassInfo.renderArea().offset().x(0).y(0)
        renderPassInfo.renderArea().extent().width(swapchain.extentW).height(swapchain.extentH)

        vkCmdBeginRenderPass(cmdBuf, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE)
        // TODO(vulkan-renderer): 2D 배치 파이프라인이 완성되면 여기서 RenderCommand를 실행합니다.
        vkCmdEndRenderPass(cmdBuf)

        vkCheck(vkEndCommandBuffer(cmdBuf), "vkEndCommandBuffer 실패")
    }

    private fun submit(cmdBuf: VkCommandBuffer, sync: FrameSync, imageIndex: Int) = stackPush().use { stack ->
        val pCmdBuf = stack.mallocPointer(1)
        pCmdBuf.put(0, cmdBuf.address())

        val submitInfo = VkSubmitInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
            .waitSemaphoreCount(1)
            .pWaitSemaphores(stack.longs(sync.imageAvailable))
            .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
            .pCommandBuffers(pCmdBuf)
            .pSignalSemaphores(stack.longs(renderFinishedSemaphores[imageIndex]))

        vkCheck(vkQueueSubmit(device.graphicsQueue, submitInfo, sync.inFlightFence), "vkQueueSubmit 실패")
    }

    private fun present(imageIndex: Int): Int = stackPush().use { stack ->
        val presentInfo = VkPresentInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
            .pWaitSemaphores(stack.longs(renderFinishedSemaphores[imageIndex]))
            .swapchainCount(1)
            .pSwapchains(stack.longs(swapchain.handle))
            .pImageIndices(stack.ints(imageIndex))
        vkQueuePresentKHR(device.presentQueue, presentInfo)
    }

    /** 창 리사이즈/최소화 복귀/out-of-date 시 스왑체인+프레임버퍼를 다시 만듭니다. */
    fun recreateSwapchain(fbWidth: Int, fbHeight: Int) {
        if (fbWidth <= 0 || fbHeight <= 0) return
        device.waitIdle()

        val old = swapchain
        swapchain = VulkanSwapchain.create(physical, device, surface, fbWidth, fbHeight, oldSwapchain = old.handle)
        old.destroy(device.handle)

        VulkanFramebuffers.destroy(device.handle, framebuffers)
        framebuffers = VulkanFramebuffers.create(device.handle, renderPass, swapchain.imageViews, swapchain.extentW, swapchain.extentH)

        // 이미지 개수가 바뀔 수 있으므로 renderFinished 세마포어 배열도 다시 만듭니다.
        VulkanSyncObjects.destroySemaphores(device.handle, renderFinishedSemaphores)
        renderFinishedSemaphores = VulkanSyncObjects.createSemaphores(device.handle, swapchain.images.size)

        log.info("[Vulkan] 스왑체인 재생성 — {}×{}", swapchain.extentW, swapchain.extentH)
    }

    fun destroy() {
        device.waitIdle()
        VulkanSyncObjects.destroy(device.handle, syncs)
        VulkanSyncObjects.destroySemaphores(device.handle, renderFinishedSemaphores)
        VulkanCommandPool.destroy(device.handle, commandPool)
        VulkanFramebuffers.destroy(device.handle, framebuffers)
        VulkanRenderPass.destroy(device.handle, renderPass)
        swapchain.destroy(device.handle)
        device.destroy()
        VulkanSurface.destroy(instance.handle, surface)
        instance.destroy()
        log.info("[Vulkan] VulkanContext 해제 완료")
    }
}
