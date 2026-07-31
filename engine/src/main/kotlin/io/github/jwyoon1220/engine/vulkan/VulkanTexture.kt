package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkImageMemoryBarrier
import org.lwjgl.vulkan.VkImageViewCreateInfo
import org.lwjgl.vulkan.VkQueue
import java.nio.ByteBuffer

internal fun transitionImageLayout(cmd: VkCommandBuffer, image: Long, oldLayout: Int, newLayout: Int) =
    stackPush().use { stack ->
        val barriers = VkImageMemoryBarrier.calloc(1, stack)
        val barrier = barriers[0]
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout)
            .newLayout(newLayout)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image)
        barrier.subresourceRange()
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .baseMipLevel(0).levelCount(1)
            .baseArrayLayer(0).layerCount(1)

        val srcStage: Int; val dstStage: Int
        if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            barrier.srcAccessMask(0)
            barrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT
        } else if (oldLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            barrier.srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
            barrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
            srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT
        } else {
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
            srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT
            dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
        }

        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barriers)
    }

internal fun copyBufferToImageRegion(
    cmd: VkCommandBuffer, buffer: Long, image: Long,
    x: Int, y: Int, width: Int, height: Int
) = stackPush().use { stack ->
    val regions = VkBufferImageCopy.calloc(1, stack)
    val region = regions[0]
        .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
    region.imageSubresource()
        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
        .mipLevel(0).baseArrayLayer(0).layerCount(1)
    region.imageOffset().x(x).y(y).z(0)
    region.imageExtent().width(width).height(height).depth(1)

    vkCmdCopyBufferToImage(cmd, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions)
}

/** GPU 텍스처 — 이미지 + 이미지뷰. 디스크립터 셋은 [Vulkan2DPipeline]이 만듭니다(샘플러를 공유하므로). */
class VulkanTexture private constructor(
    val image: VmaImage,
    val imageView: Long,
    val format: Int,
    val width: Int,
    val height: Int
) {
    companion object {
        /**
         * RGBA8 픽셀 데이터로 텍스처를 만들고 스테이징 버퍼를 통해 즉시 업로드합니다.
         * 초기화 시에만 호출하세요(스테이징 버퍼 생성 + one-time 커맨드 제출 + 대기 — 비용이 큼).
         */
        fun createFromRgba(
            device: VkDevice, allocator: VmaAllocator, commandPool: Long, queue: VkQueue,
            width: Int, height: Int, pixels: ByteBuffer
        ): VulkanTexture = create(device, allocator, commandPool, queue, VK_FORMAT_R8G8B8A8_UNORM, width, height, pixels, 4)

        /** 단일 채널(R8) 텍스처 — SDF 폰트 아틀라스용. */
        fun createFromR8(
            device: VkDevice, allocator: VmaAllocator, commandPool: Long, queue: VkQueue,
            width: Int, height: Int, pixels: ByteBuffer
        ): VulkanTexture = create(device, allocator, commandPool, queue, VK_FORMAT_R8_UNORM, width, height, pixels, 1)

        private fun create(
            device: VkDevice, allocator: VmaAllocator, commandPool: Long, queue: VkQueue,
            format: Int, width: Int, height: Int, pixels: ByteBuffer, bytesPerPixel: Int
        ): VulkanTexture {
            val size = width.toLong() * height.toLong() * bytesPerPixel
            val staging = allocator.createBuffer(size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostVisible = true)
            val mapped = checkNotNull(staging.mappedData) { "스테이징 버퍼가 host-visible이 아닙니다" }
            mapped.put(0, pixels, 0, pixels.remaining())

            val image = allocator.createImage2D(
                width, height, format,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT
            )

            submitOneTimeCommands(device, commandPool, queue) { cmd ->
                transitionImageLayout(cmd, image.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                copyBufferToImageRegion(cmd, staging.buffer, image.image, 0, 0, width, height)
                transitionImageLayout(cmd, image.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            }

            allocator.destroyBuffer(staging)

            val view = createImageView(device, image.image, format)
            return VulkanTexture(image, view, format, width, height)
        }

        private fun createImageView(device: VkDevice, image: Long, format: Int): Long = stackPush().use { stack ->
            val viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format)
            viewInfo.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1)

            val pView = stack.mallocLong(1)
            vkCheck(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView(texture) 실패")
            pView[0]
        }
    }

    /**
     * 이미 SHADER_READ_ONLY_OPTIMAL 상태인 텍스처의 일부 영역만 다시 업로드합니다
     * (폰트 아틀라스에 글리프를 하나씩 추가할 때 씀). one-time 커맨드라 비용이 크므로
     * 새 글리프가 처음 등장했을 때만 호출하세요 — 매 프레임 호출 금지.
     */
    fun uploadRegion(
        device: VkDevice, allocator: VmaAllocator, commandPool: Long, queue: VkQueue,
        x: Int, y: Int, width: Int, height: Int, bytesPerPixel: Int, pixels: ByteBuffer
    ) {
        val size = width.toLong() * height.toLong() * bytesPerPixel
        val staging = allocator.createBuffer(size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostVisible = true)
        val mapped = checkNotNull(staging.mappedData) { "스테이징 버퍼가 host-visible이 아닙니다" }
        mapped.put(0, pixels, 0, pixels.remaining())

        submitOneTimeCommands(device, commandPool, queue) { cmd ->
            transitionImageLayout(cmd, image.image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
            copyBufferToImageRegion(cmd, staging.buffer, image.image, x, y, width, height)
            transitionImageLayout(cmd, image.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        }

        allocator.destroyBuffer(staging)
    }

    fun destroy(device: VkDevice, allocator: VmaAllocator) {
        vkDestroyImageView(device, imageView, null)
        allocator.destroyImage(image)
    }
}
