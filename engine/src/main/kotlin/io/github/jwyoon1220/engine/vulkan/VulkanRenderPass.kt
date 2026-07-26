package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkAttachmentDescription
import org.lwjgl.vulkan.VkAttachmentReference
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkRenderPassCreateInfo
import org.lwjgl.vulkan.VkSubpassDependency
import org.lwjgl.vulkan.VkSubpassDescription

/**
 * 컬러 어태치먼트 1개짜리 기본 렌더패스: 매 프레임 clear → store, UNDEFINED → PRESENT_SRC_KHR 레이아웃 전이.
 * 지오메트리를 실제로 그리는 2D 배치 파이프라인은 이 위에 얹는 다음 단계 작업입니다 — 지금은
 * "클리어 컬러로 스왑체인을 채우고 화면에 프레젠트"까지가 기반입니다.
 */
object VulkanRenderPass {
    fun create(device: VkDevice, colorFormat: Int): Long = stackPush().use { stack ->
        val colorAttachment = VkAttachmentDescription.calloc(1, stack)
        colorAttachment[0]
            .format(colorFormat)
            .samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
            .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
            .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)

        val colorAttachmentRef = VkAttachmentReference.calloc(1, stack)
        colorAttachmentRef[0]
            .attachment(0)
            .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)

        val subpass = VkSubpassDescription.calloc(1, stack)
        subpass[0]
            .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
            .colorAttachmentCount(1)
            .pColorAttachments(colorAttachmentRef)

        val dependency = VkSubpassDependency.calloc(1, stack)
        dependency[0]
            .srcSubpass(VK_SUBPASS_EXTERNAL)
            .dstSubpass(0)
            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
            .srcAccessMask(0)
            .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)

        val renderPassInfo = VkRenderPassCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
            .pAttachments(colorAttachment)
            .pSubpasses(subpass)
            .pDependencies(dependency)

        val pRenderPass = stack.mallocLong(1)
        vkCheck(vkCreateRenderPass(device, renderPassInfo, null, pRenderPass), "vkCreateRenderPass 실패")
        pRenderPass[0]
    }

    fun destroy(device: VkDevice, renderPass: Long) = vkDestroyRenderPass(device, renderPass, null)
}
