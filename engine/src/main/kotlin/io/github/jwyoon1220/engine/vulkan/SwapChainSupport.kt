package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR
import org.lwjgl.vulkan.VkSurfaceFormatKHR

data class SurfaceFormat(val format: Int, val colorSpace: Int)

/**
 * VkSurfaceCapabilitiesKHR의 필요한 필드만 뽑아낸 순수 Kotlin 값.
 * 원본 구조체는 MemoryStack 위에 있어 [SwapChainSupport.query]의 stack 프레임을 벗어나면 무효화되므로
 * 여기서 값만 복사해 안전하게 반환합니다. currentExtent가 (0xFFFFFFFF, 0xFFFFFFFF)면
 * "창 크기를 그대로 써도 된다"는 의미이며, [hasDefiniteExtent]로 판별합니다.
 */
data class SurfaceCapabilities(
    val minImageCount: Int,
    val maxImageCount: Int, // 0 = 제한 없음
    val currentExtentW: Int,
    val currentExtentH: Int,
    val minExtentW: Int, val minExtentH: Int,
    val maxExtentW: Int, val maxExtentH: Int,
    val currentTransform: Int
) {
    val hasDefiniteExtent: Boolean get() = currentExtentW != -1 // 0xFFFFFFFF as Int == -1
}

data class SwapChainSupportDetails(
    val capabilities: SurfaceCapabilities,
    val formats: List<SurfaceFormat>,
    val presentModes: List<Int>
)

object SwapChainSupport {
    fun query(physicalDevice: VkPhysicalDevice, surface: Long): SwapChainSupportDetails = stackPush().use { stack ->
        val caps = VkSurfaceCapabilitiesKHR.malloc(stack)
        vkCheck(
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, caps),
            "vkGetPhysicalDeviceSurfaceCapabilitiesKHR 실패"
        )

        val formatCount = stack.mallocInt(1)
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, null)
        val formats = ArrayList<SurfaceFormat>(formatCount[0])
        if (formatCount[0] > 0) {
            val pFormats = VkSurfaceFormatKHR.malloc(formatCount[0], stack)
            vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, pFormats)
            for (f in pFormats) formats.add(SurfaceFormat(f.format(), f.colorSpace()))
        }

        val presentModeCount = stack.mallocInt(1)
        vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, presentModeCount, null)
        val presentModes = ArrayList<Int>(presentModeCount[0])
        if (presentModeCount[0] > 0) {
            val pModes = stack.mallocInt(presentModeCount[0])
            vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, presentModeCount, pModes)
            for (i in 0 until presentModeCount[0]) presentModes.add(pModes[i])
        }

        SwapChainSupportDetails(
            capabilities = SurfaceCapabilities(
                minImageCount  = caps.minImageCount(),
                maxImageCount  = caps.maxImageCount(),
                currentExtentW = caps.currentExtent().width(),
                currentExtentH = caps.currentExtent().height(),
                minExtentW     = caps.minImageExtent().width(),
                minExtentH     = caps.minImageExtent().height(),
                maxExtentW     = caps.maxImageExtent().width(),
                maxExtentH     = caps.maxImageExtent().height(),
                currentTransform = caps.currentTransform()
            ),
            formats = formats,
            presentModes = presentModes
        )
    }
}
