package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkSubmitInfo

/**
 * 텍스처 업로드처럼 초기화 시 한 번만 실행하는 짧은 커맨드 버퍼를 만들어 기록/제출/대기까지 처리합니다.
 * 매 프레임 호출하는 용도가 아닙니다 — vkQueueWaitIdle로 완료를 기다리므로 비용이 큽니다.
 */
fun submitOneTimeCommands(device: VkDevice, commandPool: Long, queue: VkQueue, record: (VkCommandBuffer) -> Unit) {
    stackPush().use { stack ->
        val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            .commandPool(commandPool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(1)
        val pBuffer = stack.mallocPointer(1)
        vkCheck(vkAllocateCommandBuffers(device, allocInfo, pBuffer), "vkAllocateCommandBuffers(one-time) 실패")
        val cmdBuf = VkCommandBuffer(pBuffer[0], device)

        val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
        vkCheck(vkBeginCommandBuffer(cmdBuf, beginInfo), "vkBeginCommandBuffer(one-time) 실패")

        record(cmdBuf)

        vkCheck(vkEndCommandBuffer(cmdBuf), "vkEndCommandBuffer(one-time) 실패")

        val pCmdBuf = stack.mallocPointer(1)
        pCmdBuf.put(0, cmdBuf.address())
        val submitInfo = VkSubmitInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
            .pCommandBuffers(pCmdBuf)
        vkCheck(vkQueueSubmit(queue, submitInfo, VK_NULL_HANDLE), "vkQueueSubmit(one-time) 실패")
        vkCheck(vkQueueWaitIdle(queue), "vkQueueWaitIdle(one-time) 실패")

        vkFreeCommandBuffers(device, commandPool, pCmdBuf)
    }
}
