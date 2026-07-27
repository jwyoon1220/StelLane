package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader
import org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkDescriptorImageInfo
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo
import org.lwjgl.vulkan.VkDescriptorPoolSize
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo
import org.lwjgl.vulkan.VkPushConstantRange
import org.lwjgl.vulkan.VkSamplerCreateInfo
import org.lwjgl.vulkan.VkShaderModuleCreateInfo
import org.lwjgl.vulkan.VkVertexInputAttributeDescription
import org.lwjgl.vulkan.VkVertexInputBindingDescription
import org.lwjgl.vulkan.VkWriteDescriptorSet

/** [Vulkan2DBatcher]가 채우는 정점 하나 — pos(2f) + uv(2f) + color(4f) + mode(1f) = 36바이트. */
object Vulkan2DVertex {
    const val FLOATS_PER_VERTEX = 9
    const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

    const val MODE_SOLID    = 0f  // vColor 그대로 사용 (흰 1x1 텍스처)
    const val MODE_IMAGE    = 1f  // texture(uTex, uv) * vColor
    const val MODE_SDF_TEXT = 2f  // texture(uTex, uv).r 를 거리값으로 보고 smoothstep
}

private const val VERT_SRC = """
#version 450
layout(push_constant) uniform PushConstants {
    vec4 uViewport;    // x,y,w,h — 물리 픽셀
    vec2 uDesign;      // 논리 해상도
    vec2 uFramebuffer; // 물리 프레임버퍼 크기
} pc;

layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in vec4 aColor;
layout(location = 3) in float aMode;

layout(location = 0) out vec2 vUV;
layout(location = 1) out vec4 vColor;
layout(location = 2) out float vMode;

void main() {
    float px = pc.uViewport.x + (aPos.x / pc.uDesign.x) * pc.uViewport.z;
    float py = pc.uViewport.y + (aPos.y / pc.uDesign.y) * pc.uViewport.w;
    float nx = (px / pc.uFramebuffer.x) * 2.0 - 1.0;
    // Vulkan NDC의 Y축은 이미 아래쪽 방향 — OpenGL과 달리 뒤집을 필요가 없습니다.
    float ny = (py / pc.uFramebuffer.y) * 2.0 - 1.0;
    gl_Position = vec4(nx, ny, 0.0, 1.0);
    vUV = aUV;
    vColor = aColor;
    vMode = aMode;
}
"""

private const val FRAG_SRC = """
#version 450
layout(location = 0) in vec2 vUV;
layout(location = 1) in vec4 vColor;
layout(location = 2) in float vMode;

layout(binding = 0) uniform sampler2D uTex;

layout(location = 0) out vec4 FragColor;

void main() {
    if (vMode < 0.5) {
        FragColor = vColor;
    } else if (vMode < 1.5) {
        FragColor = texture(uTex, vUV) * vColor;
    } else {
        float dist = texture(uTex, vUV).r;
        float aa = max(fwidth(dist), 1e-4);
        float alpha = smoothstep(0.5 - aa, 0.5 + aa, dist);
        FragColor = vec4(vColor.rgb, vColor.a * alpha);
    }
}
"""

/**
 * 2D 배치 렌더링용 단일 그래픽스 파이프라인 — 사각형/도형/텍스트/이미지를 전부 이 하나의 파이프라인
 * (uber-shader, mode로 분기)으로 그립니다. 텍스처(디스크립터 셋)만 바뀔 때 draw call을 나누는 방식으로
 * [io.github.jwyoon1220.app.render.NoteRenderer]가 쓰는 GlQuadBatchRenderer와 동일한 배칭 전략입니다.
 *
 * 뷰포트/시저는 동적 상태라 리사이즈 때 파이프라인을 다시 만들 필요가 없습니다.
 */
class Vulkan2DPipeline private constructor(
    private val device: VkDevice,
    val pipeline: Long,
    val pipelineLayout: Long,
    val descriptorSetLayout: Long,
    private val descriptorPool: Long,
    val sampler: Long
) {
    companion object {
        const val PUSH_CONSTANT_SIZE = 8 * 4 // vec4 + vec2 + vec2 = 8 floats
        private const val MAX_TEXTURES = 256 // 디스크립터 풀 용량 — 텍스처(흰 1x1 + SDF 폰트 아틀라스 + 업로드 이미지들) 상한

        fun create(device: VkDevice, renderPass: Long, colorFormat: Int): Vulkan2DPipeline = stackPush().use { stack ->
            // ── 디스크립터 셋 레이아웃 (binding 0: combined image sampler) ──────────
            val binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
            binding[0]
                .binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)

            val layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(binding)
            val pSetLayout = stack.mallocLong(1)
            vkCheck(vkCreateDescriptorSetLayout(device, layoutInfo, null, pSetLayout), "vkCreateDescriptorSetLayout 실패")
            val descriptorSetLayout = pSetLayout[0]

            // ── 디스크립터 풀 ─────────────────────────────────────────────────────
            val poolSize = VkDescriptorPoolSize.calloc(1, stack)
            poolSize[0].type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_TEXTURES)
            val poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .pPoolSizes(poolSize)
                .maxSets(MAX_TEXTURES)
            val pPool = stack.mallocLong(1)
            vkCheck(vkCreateDescriptorPool(device, poolInfo, null, pPool), "vkCreateDescriptorPool 실패")
            val descriptorPool = pPool[0]

            // ── 샘플러 (모든 텍스처가 공유) ───────────────────────────────────────
            val samplerInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_LINEAR)
                .minFilter(VK_FILTER_LINEAR)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .unnormalizedCoordinates(false)
            val pSampler = stack.mallocLong(1)
            vkCheck(vkCreateSampler(device, samplerInfo, null, pSampler), "vkCreateSampler 실패")
            val sampler = pSampler[0]

            // ── 파이프라인 레이아웃 (디스크립터 셋 + 푸시 상수) ──────────────────────
            val pushConstantRange = VkPushConstantRange.calloc(1, stack)
            pushConstantRange[0].stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(PUSH_CONSTANT_SIZE)
            val pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(descriptorSetLayout))
                .pPushConstantRanges(pushConstantRange)
            val pPipelineLayout = stack.mallocLong(1)
            vkCheck(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout), "vkCreatePipelineLayout 실패")
            val pipelineLayout = pPipelineLayout[0]

            // ── 셰이더 모듈 ───────────────────────────────────────────────────────
            val vertSpirv = VulkanShaderCompiler.compileToSpirV(VERT_SRC, shaderc_glsl_vertex_shader, "quad.vert")
            val fragSpirv = VulkanShaderCompiler.compileToSpirV(FRAG_SRC, shaderc_glsl_fragment_shader, "quad.frag")
            val vertModule = createShaderModule(device, vertSpirv)
            val fragModule = createShaderModule(device, fragSpirv)
            org.lwjgl.system.MemoryUtil.memFree(vertSpirv)
            org.lwjgl.system.MemoryUtil.memFree(fragSpirv)

            val entryPoint = stack.UTF8("main")
            val shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
            shaderStages[0]
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_VERTEX_BIT)
                .module(vertModule)
                .pName(entryPoint)
            shaderStages[1]
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(fragModule)
                .pName(entryPoint)

            // ── 정점 입력 ─────────────────────────────────────────────────────────
            val bindingDesc = VkVertexInputBindingDescription.calloc(1, stack)
            bindingDesc[0].binding(0).stride(Vulkan2DVertex.STRIDE_BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)

            val attrDescs = VkVertexInputAttributeDescription.calloc(4, stack)
            attrDescs[0].binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0)       // aPos
            attrDescs[1].binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(8)       // aUV
            attrDescs[2].binding(0).location(2).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(16) // aColor
            attrDescs[3].binding(0).location(3).format(VK_FORMAT_R32_SFLOAT).offset(32)         // aMode

            val vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(bindingDesc)
                .pVertexAttributeDescriptions(attrDescs)

            val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                .primitiveRestartEnable(false)

            // ── 뷰포트/시저 (동적 상태 — 리사이즈 시 파이프라인 재생성 불필요) ──────────
            val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                .viewportCount(1)
                .scissorCount(1)

            val dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

            val rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .depthClampEnable(false)
                .rasterizerDiscardEnable(false)
                .polygonMode(VK_POLYGON_MODE_FILL)
                .lineWidth(1f)
                .cullMode(VK_CULL_MODE_NONE)
                .depthBiasEnable(false)

            val multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .sampleShadingEnable(false)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)

            // ── 알파 블렌딩 (OpenGL glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)와 동일) ──
            val colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
            colorBlendAttachment[0]
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
                .blendEnable(true)
                .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .alphaBlendOp(VK_BLEND_OP_ADD)

            val colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .logicOpEnable(false)
                .pAttachments(colorBlendAttachment)

            val pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
            pipelineInfo[0]
                .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pStages(shaderStages)
                .pVertexInputState(vertexInputInfo)
                .pInputAssemblyState(inputAssembly)
                .pViewportState(viewportState)
                .pRasterizationState(rasterizer)
                .pMultisampleState(multisampling)
                .pColorBlendState(colorBlending)
                .pDynamicState(dynamicState)
                .layout(pipelineLayout)
                .renderPass(renderPass)
                .subpass(0)

            val pPipeline = stack.mallocLong(1)
            vkCheck(
                vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                "vkCreateGraphicsPipelines(2D) 실패"
            )

            vkDestroyShaderModule(device, vertModule, null)
            vkDestroyShaderModule(device, fragModule, null)

            Vulkan2DPipeline(device, pPipeline[0], pipelineLayout, descriptorSetLayout, descriptorPool, sampler)
        }

        private fun createShaderModule(device: VkDevice, spirv: java.nio.ByteBuffer): Long = stackPush().use { stack ->
            val info = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spirv)
            val pModule = stack.mallocLong(1)
            vkCheck(vkCreateShaderModule(device, info, null, pModule), "vkCreateShaderModule 실패")
            pModule[0]
        }
    }

    /** [imageView]를 샘플링하는 디스크립터 셋을 새로 할당합니다 (텍스처 하나당 한 번). */
    fun createTextureDescriptorSet(imageView: Long): Long = stackPush().use { stack ->
        val allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
            .descriptorPool(descriptorPool)
            .pSetLayouts(stack.longs(descriptorSetLayout))
        val pSet = stack.mallocLong(1)
        vkCheck(vkAllocateDescriptorSets(device, allocInfo, pSet), "vkAllocateDescriptorSets 실패")
        val set = pSet[0]

        val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
        imageInfo[0]
            .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            .imageView(imageView)
            .sampler(sampler)

        val write = VkWriteDescriptorSet.calloc(1, stack)
        write[0]
            .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(set)
            .dstBinding(0)
            .dstArrayElement(0)
            .descriptorCount(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .pImageInfo(imageInfo)

        vkUpdateDescriptorSets(device, write, null)
        set
    }

    fun destroy() {
        vkDestroySampler(device, sampler, null)
        vkDestroyDescriptorPool(device, descriptorPool, null) // 여기 딸린 디스크립터 셋들도 함께 해제됨
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null)
        vkDestroyPipelineLayout(device, pipelineLayout, null)
        vkDestroyPipeline(device, pipeline, null)
    }
}
