package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.vma.Vma.*
import org.lwjgl.util.vma.VmaAllocationCreateInfo
import org.lwjgl.util.vma.VmaAllocationInfo
import org.lwjgl.util.vma.VmaAllocatorCreateInfo
import org.lwjgl.util.vma.VmaVulkanFunctions
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkPhysicalDevice
import java.nio.ByteBuffer

/**
 * VMA(Vulkan Memory Allocator) 핸들 래퍼 — GPU 버퍼/이미지 메모리 할당을 위임합니다.
 * 손으로 짠 vkAllocateMemory보다 서브할당/메모리 타입 선택을 훨씬 잘 처리합니다.
 */
class VmaAllocator private constructor(val handle: Long) {
    companion object {
        fun create(instance: VkInstance, physicalDevice: VkPhysicalDevice, device: VkDevice): VmaAllocator =
            stackPush().use { stack ->
                val vulkanFunctions = VmaVulkanFunctions.calloc(stack).set(instance, device)
                val createInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .physicalDevice(physicalDevice)
                    .device(device)
                    .instance(instance)
                    .pVulkanFunctions(vulkanFunctions)

                val pAllocator = stack.mallocPointer(1)
                vkCheck(vmaCreateAllocator(createInfo, pAllocator), "vmaCreateAllocator 실패")
                VmaAllocator(pAllocator[0])
            }
    }

    fun destroy() = vmaDestroyAllocator(handle)
}

/** VMA로 할당한 버퍼. [mappedData]는 host-visible + persistently-mapped로 만들었을 때만 non-null. */
class VmaBuffer(val buffer: Long, val allocation: Long, val mappedData: ByteBuffer?)

/** VMA로 할당한 이미지 (텍스처용). */
class VmaImage(val image: Long, val allocation: Long)

/**
 * GPU 버퍼를 생성합니다.
 * @param hostVisible true면 CPU에서 직접 memcpy 가능한 persistently-mapped 버퍼로 만듭니다
 *   (매 프레임 갱신되는 정점 버퍼용 — map/unmap을 매 프레임 반복하지 않기 위함).
 */
fun VmaAllocator.createBuffer(size: Long, usage: Int, hostVisible: Boolean): VmaBuffer = stackPush().use { stack ->
    val bufferInfo = VkBufferCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
        .size(size)
        .usage(usage)
        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)

    val allocInfo = VmaAllocationCreateInfo.calloc(stack)
    if (hostVisible) {
        allocInfo.usage(VMA_MEMORY_USAGE_AUTO)
        allocInfo.flags(VMA_ALLOCATION_CREATE_MAPPED_BIT or VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT)
    } else {
        allocInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE)
    }

    val pBuffer = stack.mallocLong(1)
    val pAllocation = stack.mallocPointer(1)
    val allocationInfo = VmaAllocationInfo.calloc(stack)
    vkCheck(
        vmaCreateBuffer(handle, bufferInfo, allocInfo, pBuffer, pAllocation, allocationInfo),
        "vmaCreateBuffer 실패"
    )

    val mapped = if (hostVisible) {
        val addr = allocationInfo.pMappedData()
        if (addr != 0L) MemoryUtil.memByteBuffer(addr, size.toInt()) else null
    } else null

    VmaBuffer(pBuffer[0], pAllocation[0], mapped)
}

fun VmaAllocator.destroyBuffer(buf: VmaBuffer) = vmaDestroyBuffer(handle, buf.buffer, buf.allocation)

/** 디바이스 로컬 2D 텍스처 이미지를 생성합니다 (스테이징 업로드는 호출 측 책임). */
fun VmaAllocator.createImage2D(width: Int, height: Int, format: Int, usage: Int): VmaImage = stackPush().use { stack ->
    val imageInfo = VkImageCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
        .imageType(VK_IMAGE_TYPE_2D)
        .format(format)
        .mipLevels(1)
        .arrayLayers(1)
        .samples(VK_SAMPLE_COUNT_1_BIT)
        .tiling(VK_IMAGE_TILING_OPTIMAL)
        .usage(usage)
        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
    imageInfo.extent().width(width).height(height).depth(1)

    val allocInfo = VmaAllocationCreateInfo.calloc(stack)
        .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE)

    val pImage = stack.mallocLong(1)
    val pAllocation = stack.mallocPointer(1)
    vkCheck(
        vmaCreateImage(handle, imageInfo, allocInfo, pImage, pAllocation, null),
        "vmaCreateImage 실패"
    )
    VmaImage(pImage[0], pAllocation[0])
}

fun VmaAllocator.destroyImage(img: VmaImage) = vmaDestroyImage(handle, img.image, img.allocation)
