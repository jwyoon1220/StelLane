package io.github.jwyoon1220.engine.vulkan

import io.github.jwyoon1220.engine.FontRegistry
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkQueue

/** [FontRegistry] 슬롯 인덱스 → [VulkanFontAtlas] 지연 생성/캐시. 앱 생명주기 동안 한 인스턴스만 필요. */
class VulkanFontSystem(
    private val device: VkDevice,
    private val allocator: VmaAllocator,
    private val commandPool: Long,
    private val queue: VkQueue,
    private val pipeline: Vulkan2DPipeline
) {
    private val atlases = HashMap<Int, VulkanFontAtlas?>()

    /** 슬롯에 해당하는 아틀라스를 반환합니다(폰트 리소스를 못 찾았으면 null). 처음 호출 시 ASCII를 미리 굽습니다. */
    fun atlasFor(slot: Int): VulkanFontAtlas? {
        atlases[slot]?.let { return it }
        if (atlases.containsKey(slot)) return null // 이전에 시도했다가 실패한 슬롯

        val bytes = FontRegistry.bytesOf(slot)
        val atlas = if (bytes != null) {
            VulkanFontAtlas.create(device, allocator, commandPool, queue, pipeline, bytes).also { it.prebakeAscii() }
        } else null
        atlases[slot] = atlas
        return atlas
    }

    fun destroy() = atlases.values.forEach { it?.destroy() }
}
