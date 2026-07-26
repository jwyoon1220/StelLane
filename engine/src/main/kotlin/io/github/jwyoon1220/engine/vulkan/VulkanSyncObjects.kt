package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkFenceCreateInfo
import org.lwjgl.vulkan.VkSemaphoreCreateInfo

/**
 * in-flight 프레임 하나가 쓰는 동기화 객체 — imageAvailable 세마포어 + inFlightFence만 담습니다.
 *
 * renderFinished 세마포어는 여기 포함하지 않습니다: 프레임 개수(MAX_FRAMES_IN_FLIGHT)와 스왑체인
 * 이미지 개수가 다를 수 있어서, 프레임 단위로 이 세마포어를 재사용하면 "이전에 이 이미지를 프레젠트한
 * present 오퍼레이션이 세마포어를 다 쓰기 전에 다른 프레임이 같은 세마포어를 다시 signal하는" 경합이
 * 생길 수 있습니다(VUID-vkQueueSubmit-pSignalSemaphores-00067). 그래서 renderFinished는
 * [VulkanContext]가 스왑체인 이미지 개수만큼 별도로 관리하고, acquire된 imageIndex로 인덱싱합니다.
 */
class FrameSync(val imageAvailable: Long, val inFlightFence: Long)

/** [count]개(보통 MAX_FRAMES_IN_FLIGHT)만큼 imageAvailable 세마포어 + 초기 signaled 펜스를 생성합니다. */
object VulkanSyncObjects {
    fun create(device: VkDevice, count: Int): List<FrameSync> = stackPush().use { stack ->
        val semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
        val fenceInfo = VkFenceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            .flags(VK_FENCE_CREATE_SIGNALED_BIT) // 첫 프레임에서 vkWaitForFences가 즉시 통과하도록

        (0 until count).map {
            val pImgAvail = stack.mallocLong(1)
            val pFence    = stack.mallocLong(1)
            vkCheck(vkCreateSemaphore(device, semaphoreInfo, null, pImgAvail), "vkCreateSemaphore(imageAvailable) 실패")
            vkCheck(vkCreateFence(device, fenceInfo, null, pFence), "vkCreateFence 실패")
            FrameSync(pImgAvail[0], pFence[0])
        }
    }

    fun destroy(device: VkDevice, syncs: List<FrameSync>) {
        syncs.forEach {
            vkDestroySemaphore(device, it.imageAvailable, null)
            vkDestroyFence(device, it.inFlightFence, null)
        }
    }

    /** 스왑체인 이미지 개수만큼의 순수 세마포어 — renderFinished처럼 이미지 인덱스로 인덱싱하는 용도. */
    fun createSemaphores(device: VkDevice, count: Int): LongArray = stackPush().use { stack ->
        val semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
        LongArray(count) {
            val pSem = stack.mallocLong(1)
            vkCheck(vkCreateSemaphore(device, semaphoreInfo, null, pSem), "vkCreateSemaphore 실패")
            pSem[0]
        }
    }

    fun destroySemaphores(device: VkDevice, semaphores: LongArray) =
        semaphores.forEach { vkDestroySemaphore(device, it, null) }
}
