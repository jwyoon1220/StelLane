package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkFramebufferCreateInfo

/** 스왑체인 이미지뷰마다 하나씩 프레임버퍼를 만듭니다. */
object VulkanFramebuffers {
    fun create(device: VkDevice, renderPass: Long, imageViews: LongArray, extentW: Int, extentH: Int): LongArray =
        stackPush().use { stack ->
            LongArray(imageViews.size) { i ->
                val attachments = stack.mallocLong(1)
                attachments.put(0, imageViews[i])
                val info = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(attachments)
                    .width(extentW)
                    .height(extentH)
                    .layers(1)
                val pFb = stack.mallocLong(1)
                vkCheck(vkCreateFramebuffer(device, info, null, pFb), "vkCreateFramebuffer 실패")
                pFb[0]
            }
        }

    fun destroy(device: VkDevice, framebuffers: LongArray) = framebuffers.forEach { vkDestroyFramebuffer(device, it, null) }
}
