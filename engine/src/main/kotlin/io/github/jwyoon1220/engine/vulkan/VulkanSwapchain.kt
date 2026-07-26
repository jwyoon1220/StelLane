package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkImageViewCreateInfo
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR

/**
 * 스왑체인 + 이미지뷰. 창 크기 변경/최소화 복귀 시 [create]를 다시 호출해 재생성하고,
 * 이전 스왑체인은 새 것을 만든 뒤 [destroy]하세요(oldSwapchain 파라미터로 넘겨 자원 재사용을 돕습니다).
 */
class VulkanSwapchain private constructor(
    val handle: Long,
    val images: LongArray,
    val imageViews: LongArray,
    val format: Int,
    val extentW: Int,
    val extentH: Int
) {
    companion object {
        fun create(
            physical: PickedPhysicalDevice,
            device: VulkanDevice,
            surface: Long,
            windowFbW: Int,
            windowFbH: Int,
            oldSwapchain: Long = VK_NULL_HANDLE
        ): VulkanSwapchain = stackPush().use { stack ->
            val support = SwapChainSupport.query(physical.handle, surface)
            val surfaceFormat = chooseFormat(support.formats)
            val presentMode = choosePresentMode(support.presentModes)
            val (extentW, extentH) = chooseExtent(support.capabilities, windowFbW, windowFbH)

            var imageCount = support.capabilities.minImageCount + 1
            if (support.capabilities.maxImageCount > 0 && imageCount > support.capabilities.maxImageCount) {
                imageCount = support.capabilities.maxImageCount
            }

            val createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .surface(surface)
                .minImageCount(imageCount)
                .imageFormat(surfaceFormat.format)
                .imageColorSpace(surfaceFormat.colorSpace)
                .imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                .preTransform(support.capabilities.currentTransform)
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                .presentMode(presentMode)
                .clipped(true)
                .oldSwapchain(oldSwapchain)
            createInfo.imageExtent().width(extentW).height(extentH)

            val indices = physical.queueFamilies
            if (indices.graphicsFamily != indices.presentFamily) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                createInfo.pQueueFamilyIndices(stack.ints(indices.graphicsFamily!!, indices.presentFamily!!))
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
            }

            val pSwapchain = stack.mallocLong(1)
            vkCheck(vkCreateSwapchainKHR(device.handle, createInfo, null, pSwapchain), "vkCreateSwapchainKHR 실패")
            val swapchain = pSwapchain[0]

            val countBuf = stack.mallocInt(1)
            vkGetSwapchainImagesKHR(device.handle, swapchain, countBuf, null)
            val pImages = stack.mallocLong(countBuf[0])
            vkGetSwapchainImagesKHR(device.handle, swapchain, countBuf, pImages)
            val images = LongArray(countBuf[0]) { pImages[it] }
            val imageViews = LongArray(images.size) { i -> createImageView(device.handle, images[i], surfaceFormat.format) }

            VulkanSwapchain(swapchain, images, imageViews, surfaceFormat.format, extentW, extentH)
        }

        private fun createImageView(device: VkDevice, image: Long, format: Int): Long = stackPush().use { stack ->
            val viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format)
            viewInfo.components()
                .r(VK_COMPONENT_SWIZZLE_IDENTITY)
                .g(VK_COMPONENT_SWIZZLE_IDENTITY)
                .b(VK_COMPONENT_SWIZZLE_IDENTITY)
                .a(VK_COMPONENT_SWIZZLE_IDENTITY)
            viewInfo.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)

            val pView = stack.mallocLong(1)
            vkCheck(vkCreateImageView(device, viewInfo, null, pView), "vkCreateImageView 실패")
            pView[0]
        }

        private fun chooseFormat(formats: List<SurfaceFormat>): SurfaceFormat =
            formats.firstOrNull { it.format == VK_FORMAT_B8G8R8A8_SRGB && it.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR }
                ?: formats.first()

        private fun choosePresentMode(modes: List<Int>): Int =
            if (VK_PRESENT_MODE_MAILBOX_KHR in modes) VK_PRESENT_MODE_MAILBOX_KHR else VK_PRESENT_MODE_FIFO_KHR

        /** currentExtent가 특수값(0xFFFFFFFF)이면 프레임버퍼 크기를 min/max 범위로 clamp해 사용합니다. */
        private fun chooseExtent(caps: SurfaceCapabilities, fbW: Int, fbH: Int): Pair<Int, Int> {
            if (caps.hasDefiniteExtent) return caps.currentExtentW to caps.currentExtentH
            val w = fbW.coerceIn(caps.minExtentW, caps.maxExtentW)
            val h = fbH.coerceIn(caps.minExtentH, caps.maxExtentH)
            return w to h
        }
    }

    fun destroy(device: VkDevice) {
        imageViews.forEach { vkDestroyImageView(device, it, null) }
        vkDestroySwapchainKHR(device, handle, null)
    }
}
