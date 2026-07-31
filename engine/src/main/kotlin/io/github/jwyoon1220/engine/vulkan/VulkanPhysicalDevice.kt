package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR
import org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkExtensionProperties
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import org.lwjgl.vulkan.VkQueueFamilyProperties
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

/** 그래픽스/프레젠트 큐 패밀리 인덱스. 같은 인덱스일 수도, 다를 수도 있습니다(GPU에 따라 다름). */
data class QueueFamilyIndices(var graphicsFamily: Int? = null, var presentFamily: Int? = null) {
    fun isComplete() = graphicsFamily != null && presentFamily != null
}

data class PickedPhysicalDevice(
    val handle: VkPhysicalDevice,
    val queueFamilies: QueueFamilyIndices,
    val name: String
)

/** GPU 선택 — 그래픽스+프레젠트 큐, VK_KHR_swapchain 확장, 유효한 스왑체인 지원을 모두 만족하는 후보 중 디스크리트 GPU를 우선합니다. */
object VulkanPhysicalDevice {
    private val log = LoggerFactory.getLogger(VulkanPhysicalDevice::class.java)

    fun pick(instance: VkInstance, surface: Long): PickedPhysicalDevice = stackPush().use { stack ->
        val count = stack.mallocInt(1)
        vkCheck(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices 실패")
        check(count[0] > 0) { "Vulkan을 지원하는 GPU가 없습니다" }

        val pDevices = stack.mallocPointer(count[0])
        vkCheck(vkEnumeratePhysicalDevices(instance, count, pDevices), "vkEnumeratePhysicalDevices 실패")

        var best: PickedPhysicalDevice? = null
        var bestScore = -1

        // 디바이스마다 독립된 스택 프레임에서 검사합니다. 하나의 프레임을 루프 전체에서 재사용하면
        // 디바이스별 임시 할당(특히 확장 목록 — 디바이스당 수십~수백 개, 항목당 260바이트)이 반복마다
        // 계속 누적되어 기본 스택 프레임 크기를 넘기고 네이티브 메모리를 오염시킬 수 있습니다
        // (실제로 2번째 GPU에서 스택 오버플로우로 JVM이 크래시하는 걸 확인 후 이 구조로 고쳤습니다).
        for (i in 0 until count[0]) {
            stackPush().use { deviceStack ->
                val device = VkPhysicalDevice(pDevices[i], instance)
                val indices = findQueueFamilies(deviceStack, device, surface)
                if (!indices.isComplete()) return@use
                if (!supportsRequiredExtensions(deviceStack, device)) return@use

                val support = SwapChainSupport.query(device, surface)
                if (support.formats.isEmpty() || support.presentModes.isEmpty()) return@use

                val props = VkPhysicalDeviceProperties.malloc(deviceStack)
                vkGetPhysicalDeviceProperties(device, props)
                val name = props.deviceNameString()
                val score = if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) 1000 else 1

                log.info("[Vulkan] 후보 GPU: {} (score={})", name, score)
                if (score > bestScore) {
                    bestScore = score
                    best = PickedPhysicalDevice(device, indices, name)
                }
            }
        }

        val picked = best
            ?: error("그래픽스+프레젠트 큐, VK_KHR_swapchain, 유효한 스왑체인 지원을 모두 만족하는 GPU를 찾지 못했습니다")
        log.info("[Vulkan] 선택된 GPU: {}", picked.name)
        picked
    }

    private fun findQueueFamilies(stack: MemoryStack, device: VkPhysicalDevice, surface: Long): QueueFamilyIndices {
        val indices = QueueFamilyIndices()
        val count = stack.mallocInt(1)
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null)
        val families = VkQueueFamilyProperties.malloc(count[0], stack)
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families)

        val presentSupport = stack.mallocInt(1)
        for (i in 0 until count[0]) {
            if (families[i].queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0) indices.graphicsFamily = i
            vkCheck(
                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, presentSupport),
                "vkGetPhysicalDeviceSurfaceSupportKHR 실패"
            )
            if (presentSupport[0] == VK_TRUE) indices.presentFamily = i
            if (indices.isComplete()) break
        }
        return indices
    }

    private fun supportsRequiredExtensions(stack: MemoryStack, device: VkPhysicalDevice): Boolean {
        val count = stack.mallocInt(1)
        vkEnumerateDeviceExtensionProperties(device, null as ByteBuffer?, count, null)
        if (count[0] == 0) return false
        val props = VkExtensionProperties.malloc(count[0], stack)
        vkEnumerateDeviceExtensionProperties(device, null as ByteBuffer?, count, props)
        return props.any { it.extensionNameString() == VK_KHR_SWAPCHAIN_EXTENSION_NAME }
    }
}
