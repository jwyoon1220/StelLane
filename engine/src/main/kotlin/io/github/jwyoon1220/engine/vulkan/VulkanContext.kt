package io.github.jwyoon1220.engine.vulkan

import io.github.jwyoon1220.engine.VideoBackground
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.KHRSurface.*
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
    private var renderFinishedSemaphores: LongArray,
    private val allocator: VmaAllocator,
    val pipeline2D: Vulkan2DPipeline,
    private val whiteTexture: VulkanTexture,
    val whiteTextureDescriptorSet: Long,
    val batcher: Vulkan2DBatcher,
    val fontSystem: VulkanFontSystem,
    val imageCache: VulkanImageCache,
    val videoTexture: VulkanVideoTexture,
    private var vsyncEnabled: Boolean
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
            enableValidation: Boolean = true,
            vsync: Boolean = true
        ): VulkanContext {
            val instance = VulkanInstance.create(appName, enableValidation)
            val surface = VulkanSurface.create(instance.handle, windowHandle)
            val physical = VulkanPhysicalDevice.pick(instance.handle, surface)
            val device = VulkanDevice.create(physical)
            val swapchain = VulkanSwapchain.create(physical, device, surface, fbWidth, fbHeight, vsync)
            val renderPass = VulkanRenderPass.create(device.handle, swapchain.format)
            val framebuffers = VulkanFramebuffers.create(device.handle, renderPass, swapchain.imageViews, swapchain.extentW, swapchain.extentH)
            val commandPool = VulkanCommandPool.create(device.handle, physical.queueFamilies.graphicsFamily!!)
            val commandBuffers = VulkanCommandPool.allocateBuffers(device.handle, commandPool, MAX_FRAMES_IN_FLIGHT)
            val syncs = VulkanSyncObjects.create(device.handle, MAX_FRAMES_IN_FLIGHT)
            // renderFinished는 프레임이 아니라 "스왑체인 이미지"당 하나 — 자세한 이유는 FrameSync 문서 참고.
            val renderFinished = VulkanSyncObjects.createSemaphores(device.handle, swapchain.images.size)

            val allocator = VmaAllocator.create(instance.handle, physical.handle, device.handle)
            val pipeline2D = Vulkan2DPipeline.create(device.handle, renderPass, swapchain.format)

            // 1x1 흰 텍스처 — 단색 채우기를 "이미지 모드" 셰이더 경로로 그리기 위한 기본 텍스처
            // (GlQuadBatchRenderer의 whiteTexture와 동일한 용도).
            val whitePixel = MemoryUtil.memAlloc(4).apply {
                put(0, 0xFF.toByte()); put(1, 0xFF.toByte()); put(2, 0xFF.toByte()); put(3, 0xFF.toByte())
            }
            val whiteTexture = VulkanTexture.createFromRgba(device.handle, allocator, commandPool, device.graphicsQueue, 1, 1, whitePixel)
            MemoryUtil.memFree(whitePixel)
            val whiteDescSet = pipeline2D.createTextureDescriptorSet(whiteTexture.imageView)

            val batcher = Vulkan2DBatcher(pipeline2D, allocator, MAX_FRAMES_IN_FLIGHT)
            val fontSystem = VulkanFontSystem(device.handle, allocator, commandPool, device.graphicsQueue, pipeline2D)
            val imageCache = VulkanImageCache(device.handle, allocator, commandPool, device.graphicsQueue, pipeline2D)
            val videoTexture = VulkanVideoTexture(device.handle, allocator, pipeline2D, MAX_FRAMES_IN_FLIGHT)

            log.info(
                "[Vulkan] 부트스트랩 완료 — {}×{}, {}개 스왑체인 이미지, imageFormat={}(UNORM={}, SRGB={}), presentMode={}(IMMEDIATE={}, MAILBOX={}, FIFO={}), vsync={}",
                swapchain.extentW, swapchain.extentH, swapchain.images.size,
                swapchain.format, VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_B8G8R8A8_SRGB,
                swapchain.presentMode, VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_FIFO_KHR,
                vsync
            )
            return VulkanContext(
                windowHandle, instance, surface, physical, device, swapchain, renderPass, framebuffers,
                commandPool, commandBuffers, syncs, renderFinished,
                allocator, pipeline2D, whiteTexture, whiteDescSet, batcher, fontSystem, imageCache, videoTexture,
                vsync
            )
        }
    }

    /**
     * RGB 클리어 컬러 (0f~1f) — 렌더패스가 스왑체인 전체를 이 색으로 지우므로, 실제 게임 화면
     * 바깥의 letterbox/pillarbox 여백에도 그대로 보입니다. [NanoVGBackend]가 이 여백을 순수 검정
     * (glClearColor(0,0,0,1))으로 지우는 것과 맞춰야 합니다 — 예전엔 진보라였는데, 그러면 Vulkan에서만
     * letterbox 여백에 옅은 색 필터가 낀 것처럼 보였습니다.
     */
    var clearColor: FloatArray = floatArrayOf(0f, 0f, 0f)

    private var currentFrame = 0
    private var pendingCapturePath: String? = null
    private var captureStaging: VmaBuffer? = null

    /**
     * 디버그 전용 — 다음 프레임을 PNG로 덤프합니다(스크린샷 도구 없이 색/블러 문제를 직접 검증하기
     * 위함). vkQueuePresentKHR로 프레젠테이션 엔진에 소유권이 넘어간 뒤에 이미지를 건드리면
     * 스펙 위반(검증 오류 "이미지가 획득되지 않음")이라 신뢰할 수 없는 내용을 읽게 됩니다 — 그래서
     * 복사 커맨드는 [recordCommandBuffer]가 렌더패스 종료 직후, 아직 앱이 소유한 상태에서 같은
     * 커맨드 버퍼 안에 기록합니다. 절대 매 프레임 호출하지 마세요(다음 [drawFrame] 호출에서 대기 발생).
     */
    fun requestFrameCapture(path: String) {
        pendingCapturePath = path
    }

    private fun recordCaptureIfPending(cmdBuf: VkCommandBuffer, imageIndex: Int) {
        if (pendingCapturePath == null) return
        val w = swapchain.extentW; val h = swapchain.extentH
        val staging = allocator.createBuffer(w.toLong() * h.toLong() * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, hostVisible = true)
        captureStaging = staging

        stackPush().use { stack ->
            val toTransferSrc = org.lwjgl.vulkan.VkImageMemoryBarrier.calloc(1, stack)
            toTransferSrc[0]
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(swapchain.images[imageIndex])
                .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
            toTransferSrc[0].subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
            vkCmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransferSrc)

            val region = org.lwjgl.vulkan.VkBufferImageCopy.calloc(1, stack)
            region[0].bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
            region[0].imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
            region[0].imageOffset().x(0).y(0).z(0)
            region[0].imageExtent().width(w).height(h).depth(1)
            vkCmdCopyImageToBuffer(cmdBuf, swapchain.images[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging.buffer, region)

            val backToPresent = org.lwjgl.vulkan.VkImageMemoryBarrier.calloc(1, stack)
            backToPresent[0]
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(swapchain.images[imageIndex])
                .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .dstAccessMask(0)
            backToPresent[0].subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
            vkCmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, backToPresent)
        }
    }

    /** [recordCaptureIfPending]가 기록한 복사가 GPU에서 끝난 뒤([drawFrame] 안에서 present 이후 호출) PNG로 씁니다. */
    private fun finishPendingCaptureIfAny() {
        val path = pendingCapturePath ?: return
        val staging = captureStaging ?: return
        device.waitIdle() // 이 프레임의 vkCmdCopyImageToBuffer가 확실히 끝난 뒤 읽기 위함 — 디버그 전용이라 허용

        val w = swapchain.extentW; val h = swapchain.extentH
        val mapped = checkNotNull(staging.mappedData)
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        // 스왑체인 포맷 B8G8R8A8_UNORM — 메모리 바이트 순서는 B,G,R,A (Vulkan 포맷 이름이 곧 바이트 순서).
        for (y in 0 until h) {
            for (x in 0 until w) {
                val o = (y * w + x) * 4
                val b = mapped.get(o).toInt() and 0xFF
                val g = mapped.get(o + 1).toInt() and 0xFF
                val r = mapped.get(o + 2).toInt() and 0xFF
                val a = mapped.get(o + 3).toInt() and 0xFF
                img.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        allocator.destroyBuffer(staging)
        captureStaging = null
        pendingCapturePath = null

        javax.imageio.ImageIO.write(img, "png", java.io.File(path))
        log.info("[Vulkan] 디버그 스크린샷 저장: {} ({}×{})", path, w, h)
    }

    /**
     * 한 프레임을 그리고 프레젠트합니다. [fbWidth]/[fbHeight]는 호출측(창 리사이즈 콜백 등)이
     * 최신 프레임버퍼 크기를 넘겨줘야 하며, out-of-date/suboptimal이면 자동으로 스왑체인을 재생성합니다.
     *
     * @param scale/offsetX/offsetY letterbox 변환값 — [io.github.jwyoon1220.engine.Renderer]가 계산해 넘겨준 값
     * @param designW/designH 논리 해상도 (보통 1280×720)
     * @param draw2D 렌더패스 begin 이후, end 이전에 호출됩니다 — [batcher]로 실제 지오메트리를 그리세요.
     *   흰 텍스처가 기본 바인딩되어 있으니 단색 채우기는 바로 그릴 수 있습니다.
     */
    fun drawFrame(
        fbWidth: Int, fbHeight: Int,
        scale: Float = 1f, offsetX: Float = 0f, offsetY: Float = 0f,
        designW: Float = fbWidth.toFloat(), designH: Float = fbHeight.toFloat(),
        videoBackground: VideoBackground? = null,
        draw2D: (Vulkan2DBatcher) -> Unit = {}
    ) {
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
        recordCommandBuffer(cmdBuf, imageIndex, scale, offsetX, offsetY, designW, designH, videoBackground, draw2D)
        submit(cmdBuf, sync, imageIndex)

        val presentResult = present(imageIndex)
        if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR) {
            recreateSwapchain(fbWidth, fbHeight)
        } else {
            vkCheck(presentResult, "vkQueuePresentKHR 실패")
        }
        finishPendingCaptureIfAny()

        currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT
    }

    private fun recordCommandBuffer(
        cmdBuf: VkCommandBuffer, imageIndex: Int,
        scale: Float, offsetX: Float, offsetY: Float, designW: Float, designH: Float,
        videoBackground: VideoBackground?,
        draw2D: (Vulkan2DBatcher) -> Unit
    ) = stackPush().use { stack ->
        vkCheck(vkResetCommandBuffer(cmdBuf, 0), "vkResetCommandBuffer 실패")

        val beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
        vkCheck(vkBeginCommandBuffer(cmdBuf, beginInfo), "vkBeginCommandBuffer 실패")

        // 렌더패스 시작 전(vkCmdCopyBufferToImage 등 전송 커맨드는 렌더패스 안에서 쓸 수 없음)
        if (videoBackground != null) videoTexture.recordUpload(cmdBuf, currentFrame, videoBackground)

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

        batcher.beginFrame(cmdBuf, currentFrame, swapchain.extentW, swapchain.extentH, scale, offsetX, offsetY, designW, designH)
        batcher.bindTexture(whiteTextureDescriptorSet)
        draw2D(batcher)
        batcher.endFrame()

        vkCmdEndRenderPass(cmdBuf)

        // 렌더패스 종료 직후(finalLayout=PRESENT_SRC_KHR로 이미 전이됨) — 아직 present() 전이라
        // 이 커맨드 버퍼/앱이 이미지 소유권을 갖고 있는 유일한 안전한 구간입니다.
        recordCaptureIfPending(cmdBuf, imageIndex)

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
        swapchain = VulkanSwapchain.create(physical, device, surface, fbWidth, fbHeight, vsyncEnabled, oldSwapchain = old.handle)
        old.destroy(device.handle)

        VulkanFramebuffers.destroy(device.handle, framebuffers)
        framebuffers = VulkanFramebuffers.create(device.handle, renderPass, swapchain.imageViews, swapchain.extentW, swapchain.extentH)

        // 이미지 개수가 바뀔 수 있으므로 renderFinished 세마포어 배열도 다시 만듭니다.
        VulkanSyncObjects.destroySemaphores(device.handle, renderFinishedSemaphores)
        renderFinishedSemaphores = VulkanSyncObjects.createSemaphores(device.handle, swapchain.images.size)

        log.info("[Vulkan] 스왑체인 재생성 — {}×{} vsync={}", swapchain.extentW, swapchain.extentH, vsyncEnabled)
    }

    /**
     * VSync를 켜고 끕니다. Vulkan의 present mode는 스왑체인 생성 시 고정되므로(OpenGL의
     * glfwSwapInterval처럼 즉시 바꿀 수 없음) 스왑체인을 통째로 다시 만들어 적용합니다 — 자주
     * 호출할 일은 없으므로(설정 화면에서 토글할 때뿐) 비용 문제는 없습니다.
     */
    fun setVSync(enabled: Boolean) {
        if (enabled == vsyncEnabled) return
        vsyncEnabled = enabled
        recreateSwapchain(swapchain.extentW, swapchain.extentH)
    }

    fun destroy() {
        device.waitIdle()
        videoTexture.destroy()
        imageCache.destroy()
        fontSystem.destroy()
        batcher.destroy()
        whiteTexture.destroy(device.handle, allocator)
        pipeline2D.destroy()
        allocator.destroy()
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
