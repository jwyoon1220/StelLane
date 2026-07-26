package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo
import org.lwjgl.vulkan.VkCommandPoolCreateInfo
import org.lwjgl.vulkan.VkDevice

object VulkanCommandPool {
    fun create(device: VkDevice, graphicsFamily: Int): Long = stackPush().use { stack ->
        val info = VkCommandPoolCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
            .queueFamilyIndex(graphicsFamily)
        val pPool = stack.mallocLong(1)
        vkCheck(vkCreateCommandPool(device, info, null, pPool), "vkCreateCommandPool 실패")
        pPool[0]
    }

    fun allocateBuffers(device: VkDevice, pool: Long, count: Int): List<VkCommandBuffer> = stackPush().use { stack ->
        val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            .commandPool(pool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(count)
        val pBuffers = stack.mallocPointer(count)
        vkCheck(vkAllocateCommandBuffers(device, allocInfo, pBuffers), "vkAllocateCommandBuffers 실패")
        (0 until count).map { VkCommandBuffer(pBuffers[it], device) }
    }

    fun destroy(device: VkDevice, pool: Long) = vkDestroyCommandPool(device, pool, null)
}
