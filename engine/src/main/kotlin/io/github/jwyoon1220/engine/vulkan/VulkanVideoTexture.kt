package io.github.jwyoon1220.engine.vulkan

import io.github.jwyoon1220.engine.UnsafeMemory
import io.github.jwyoon1220.engine.VideoBackground
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkImageViewCreateInfo
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/**
 * [VideoBackground]의 최신 프레임을 Vulkan 텍스처로 스트리밍 업로드합니다.
 * NanoVG 백엔드의 `drawNvgImage(videoNvgHandle, ...)` 경로에 대응.
 *
 * [VulkanTexture.uploadRegion]은 매 호출마다 별도 one-time 커맨드 제출 + `vkQueueWaitIdle`을 하므로
 * (SDF 폰트 아틀라스처럼 드물게 호출되는 경우엔 괜찮지만) 영상처럼 자주(~30fps) 갱신되는 텍스처에
 * 쓰면 그때마다 GPU 큐 전체가 멈춰버립니다 — 리듬게임의 프레임 페이싱에 치명적입니다.
 *
 * 대신 프레임-인-플라이트당 하나씩 host-visible 스테이징 버퍼를 갖고, 복사 커맨드는 그 프레임의
 * 메인 커맨드 버퍼(렌더패스 시작 전)에 직접 기록합니다 — 별도 제출/대기 없이 기존 프레임 파이프라인에
 * 올라탑니다. `drawFrame`이 매 프레임 [recordUpload]를 부르지만, 실제로 스테이징 버퍼에 쓰고 커맨드를
 * 기록하는 건 [VideoBackground.getFrameId]가 바뀌었을 때(=VLC가 실제로 새 프레임을 디코드했을 때)뿐입니다.
 *
 * 목적지 이미지 하나는 프레임 전체에서 재사용합니다(해상도가 바뀔 때만 재생성) — 매 업로드마다
 * 넣는 레이아웃 전환 배리어가 "이전 프레임의 셰이더 읽기가 끝난 뒤에만 다음 복사 쓰기를 시작"하도록
 * GPU 쪽에서 자동으로 직렬화해주므로, 목적지 이미지 자체는 프레임-인-플라이트별로 이중화할 필요가
 * 없습니다(스테이징 버퍼의 CPU 쓰기는 GPU 배리어가 보호해주지 못하므로 그쪽만 이중화가 필요합니다).
 */
class VulkanVideoTexture(
    private val device: VkDevice,
    private val allocator: VmaAllocator,
    private val pipeline: Vulkan2DPipeline,
    framesInFlight: Int
) {
    private var image: VmaImage? = null
    private var imageView: Long = VK_NULL_HANDLE
    private var descriptorSet: Long = -1L
    private var texW = 0
    private var texH = 0
    private var imageEverUploaded = false

    private val stagingBuffers = arrayOfNulls<VmaBuffer>(framesInFlight)
    private var stagingCapacityBytes = 0L

    private var lastFrameId = -1L

    /** 이번 프레임에 그릴 때 바인딩할 디스크립터 셋. 아직 영상 프레임이 없으면 null. */
    fun currentDescriptorSet(): Long? = descriptorSet.takeIf { it >= 0L }

    /**
     * 렌더패스 시작 전에 호출하세요. [VideoBackground]에 새 프레임이 있을 때만 실제로 스테이징
     * 버퍼에 픽셀을 쓰고 [cmd]에 복사 커맨드를 기록합니다 — 그 외에는 즉시 반환(비용 없음).
     */
    fun recordUpload(cmd: VkCommandBuffer, frameIndex: Int, video: VideoBackground) {
        if (!video.isAvailable) return
        val frameId = video.getFrameId()
        if (frameId == lastFrameId) return
        val frame = video.getCurrentFrame() ?: return
        val w = frame.width; val h = frame.height
        if (w <= 0 || h <= 0) return

        ensureStaging(w, h)
        ensureImage(w, h)

        val staging = stagingBuffers[frameIndex] ?: return
        val mapped = staging.mappedData ?: return
        writeRgba(mapped, frame)

        val img = image ?: return
        val fromLayout = if (imageEverUploaded) VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL else VK_IMAGE_LAYOUT_UNDEFINED
        transitionImageLayout(cmd, img.image, fromLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
        copyBufferToImageRegion(cmd, staging.buffer, img.image, 0, 0, w, h)
        transitionImageLayout(cmd, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        imageEverUploaded = true

        lastFrameId = frameId
    }

    private fun ensureStaging(w: Int, h: Int) {
        val needed = w.toLong() * h.toLong() * 4L
        if (needed <= stagingCapacityBytes && stagingBuffers[0] != null) return
        // 해상도가 늘어나는 경우(거의 곡 로드 시 1회) 모든 프레임-인-플라이트 슬롯을 다시 만듭니다.
        // 지금 처리 중인 프레임의 슬롯이 아닌 다른 슬롯은 아직 이전 프레임의 vkCmdCopyBufferToImage가
        // GPU에서 실행 중일 수 있으므로(그 슬롯의 펜스를 이번 호출에서 기다리지 않았음), destroyImageOnly와
        // 같은 이유로 파괴 전에 대기가 필요합니다.
        if (stagingBuffers[0] != null) vkDeviceWaitIdle(device)
        stagingBuffers.indices.forEach { i ->
            stagingBuffers[i]?.let { allocator.destroyBuffer(it) }
            stagingBuffers[i] = allocator.createBuffer(needed, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostVisible = true)
        }
        stagingCapacityBytes = needed
    }

    private fun ensureImage(w: Int, h: Int) {
        if (image != null && texW == w && texH == h) return
        destroyImageOnly()
        val img = allocator.createImage2D(w, h, VK_FORMAT_B8G8R8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
        val view = createImageView(w, h, img.image)
        image = img
        imageView = view
        descriptorSet = pipeline.createTextureDescriptorSet(view)
        texW = w; texH = h
        imageEverUploaded = false
    }

    private fun createImageView(w: Int, h: Int, imageHandle: Long): Long = stackPush().use { stack ->
        val viewInfo = VkImageViewCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
            .image(imageHandle)
            .viewType(VK_IMAGE_VIEW_TYPE_2D)
            .format(VK_FORMAT_B8G8R8A8_UNORM)
        viewInfo.subresourceRange()
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .baseMipLevel(0).levelCount(1)
            .baseArrayLayer(0).layerCount(1)
        val pView = stack.mallocLong(1)
        vkCheck(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView(video) 실패")
        pView[0]
    }

    /**
     * `BufferedImage.TYPE_INT_ARGB`의 픽셀은 자바 int로 `0xAARRGGBB`인데, x86/x64는 리틀엔디안이라
     * 그 int가 메모리에 실제로 깔리는 바이트 순서는 이미 B,G,R,A입니다 — 그래서 목적지 이미지
     * 포맷을 [VK_FORMAT_B8G8R8A8_UNORM]으로 맞추면 픽셀마다 변환할 필요 없이 IntArray를 통째로
     * `memcpy`할 수 있습니다. 예전엔 픽셀당 4번(R/G/B/A) `ByteBuffer.put`을 불렀는데, 1920×1080
     * 영상 한 프레임에서만 800만 번 넘게 호출돼 프로파일링 최상위 핫스팟이었습니다 — 벌크 복사
     * 하나로 줄이면 그 비용이 사실상 사라집니다.
     */
    private fun writeRgba(mapped: java.nio.ByteBuffer, frame: BufferedImage) {
        val pixels = (frame.raster.dataBuffer as DataBufferInt).data
        val destAddr = MemoryUtil.memAddress(mapped)
        UnsafeMemory.UNSAFE.copyMemory(
            pixels, UnsafeMemory.ARRAY_INT_BASE_OFFSET,
            null, destAddr,
            pixels.size.toLong() * 4L
        )
    }

    private fun destroyImageOnly() {
        // 해상도가 바뀌어 여기 도달하면(다른 곡의 영상), 직전 프레임들의 draw call이 이 imageView를
        // 참조하는 디스크립터 셋을 GPU에서 아직 실행 중일 수 있습니다 — vkDestroyImageView는 그 실행이
        // 끝난 뒤에만 허용되므로(VUID-vkDestroyImageView-imageView-01026) 파괴 전에 대기가 필요합니다.
        // 해상도 변경은 곡 로드 시점에만 드물게 일어나므로 이 대기가 매 프레임 비용으로 번지지 않습니다.
        if (image != null) vkDeviceWaitIdle(device)
        if (imageView != VK_NULL_HANDLE) vkDestroyImageView(device, imageView, null)
        image?.let { allocator.destroyImage(it) }
        image = null; imageView = VK_NULL_HANDLE; descriptorSet = -1L
    }

    fun destroy() {
        destroyImageOnly()
        stagingBuffers.forEach { it?.let { b -> allocator.destroyBuffer(b) } }
    }
}
