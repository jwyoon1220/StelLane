package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures
import org.lwjgl.vulkan.VkQueue

/** 논리 디바이스 + 그래픽스/프레젠트 큐 핸들. 같은 패밀리면 큐 생성 정보를 하나로 합칩니다(중복 생성 방지). */
class VulkanDevice private constructor(
    val handle: VkDevice,
    val graphicsQueue: VkQueue,
    val presentQueue: VkQueue
) {
    companion object {
        fun create(physical: PickedPhysicalDevice): VulkanDevice = stackPush().use { stack ->
            val indices = physical.queueFamilies
            val uniqueFamilies = linkedSetOf(indices.graphicsFamily!!, indices.presentFamily!!)

            val queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueFamilies.size, stack)
            val priority = stack.mallocFloat(1)
            priority.put(0, 1.0f)
            uniqueFamilies.forEachIndexed { i, family ->
                queueCreateInfos[i]
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(family)
                    .pQueuePriorities(priority)
            }

            val features = VkPhysicalDeviceFeatures.calloc(stack)

            val createInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueCreateInfos)
                .pEnabledFeatures(features)
                .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)))

            val pDevice = stack.mallocPointer(1)
            vkCheck(vkCreateDevice(physical.handle, createInfo, null, pDevice), "vkCreateDevice 실패")
            val device = VkDevice(pDevice[0], physical.handle, createInfo)

            val pQueue = stack.mallocPointer(1)
            vkGetDeviceQueue(device, indices.graphicsFamily!!, 0, pQueue)
            val graphicsQueue = VkQueue(pQueue[0], device)
            vkGetDeviceQueue(device, indices.presentFamily!!, 0, pQueue)
            val presentQueue = VkQueue(pQueue[0], device)

            VulkanDevice(device, graphicsQueue, presentQueue)
        }
    }

    fun waitIdle() = vkDeviceWaitIdle(handle)
    fun destroy() = vkDestroyDevice(handle, null)
}
