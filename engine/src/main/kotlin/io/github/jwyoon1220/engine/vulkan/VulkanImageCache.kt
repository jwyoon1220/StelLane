package io.github.jwyoon1220.engine.vulkan

import io.github.jwyoon1220.engine.UnsafeMemory
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkQueue
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/**
 * [java.awt.image.BufferedImage] → Vulkan 텍스처 캐시. [io.github.jwyoon1220.engine.NvgDrawContext.getOrUploadImage]와
 * 동일한 정체성(identityHashCode) 캐싱 규칙을 씁니다. 반환하는 `Int` 핸들은 이 캐시 안에서만
 * 의미가 있는 자체 번호입니다 — NanoVG 이미지 핸들과는 다른 번호 체계입니다.
 *
 * 비디오 배경 등 NanoVG 전용 정수 핸들(`drawNvgImage`)은 이 캐시가 다루지 않습니다 — 비디오
 * 배경은 아직 Vulkan에서 지원하지 않는 별도 known-gap입니다(VideoBackground 자체가 GL 텍스처
 * 전용 업로드 경로만 갖고 있음).
 */
class VulkanImageCache(
    private val device: VkDevice,
    private val allocator: VmaAllocator,
    private val commandPool: Long,
    private val queue: VkQueue,
    private val pipeline: Vulkan2DPipeline
) {
    private class Entry(val handle: Int, val texture: VulkanTexture, val descriptorSet: Long)

    private val byIdentity = HashMap<Int, Entry>()
    private val byHandle = HashMap<Int, Entry>()
    private var nextHandle = 1

    /** 이미지를 업로드(또는 캐시에서 재사용)하고 핸들을 반환합니다. 실패하면 -1. */
    fun getOrUpload(img: BufferedImage): Int {
        val key = System.identityHashCode(img)
        byIdentity[key]?.let { return it.handle }

        val rgba = toRgbaBuffer(img) ?: return -1
        val texture = try {
            VulkanTexture.createFromRgba(device, allocator, commandPool, queue, img.width, img.height, rgba)
        } finally {
            MemoryUtil.memFree(rgba)
        }
        val descSet = pipeline.createTextureDescriptorSet(texture.imageView)
        val entry = Entry(nextHandle++, texture, descSet)
        byIdentity[key] = entry
        byHandle[entry.handle] = entry
        return entry.handle
    }

    fun descriptorSetFor(handle: Int): Long? = byHandle[handle]?.descriptorSet

    fun invalidate(img: BufferedImage) {
        val key = System.identityHashCode(img)
        val entry = byIdentity.remove(key) ?: return
        byHandle.remove(entry.handle)
        entry.texture.destroy(device, allocator)
    }

    private fun toRgbaBuffer(img: BufferedImage): java.nio.ByteBuffer? {
        val src = if (img.type == BufferedImage.TYPE_INT_ARGB || img.type == BufferedImage.TYPE_INT_ARGB_PRE) img
        else {
            val tmp = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
            val g2 = tmp.createGraphics()
            g2.drawImage(img, 0, 0, null)
            g2.dispose()
            tmp
        }
        val pixels = (src.raster.dataBuffer as? DataBufferInt)?.data ?: return null
        val buf = MemoryUtil.memAlloc(pixels.size * 4)
        // 픽셀당 4번 ByteBuffer.put 대신(VulkanVideoTexture.writeRgba와 같은 이유로 느림) R,G,B,A를
        // 정수 하나로 묶어 Unsafe로 한 번에 씁니다. 목적지가 R8G8B8A8이라(리틀엔디안 자연 배열 순서인
        // B,G,R,A와 다름) 완전한 memcpy는 못 쓰지만, 픽셀당 호출 수는 4→1로 줄어듭니다.
        val addr = MemoryUtil.memAddress(buf)
        val unsafe = UnsafeMemory.UNSAFE
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            val a = (pixel ushr 24) and 0xFF
            unsafe.putInt(addr + i.toLong() * 4L, (a shl 24) or (b shl 16) or (g shl 8) or r)
        }
        buf.position(pixels.size * 4)
        buf.flip()
        return buf
    }

    fun destroy() = byHandle.values.forEach { it.texture.destroy(device, allocator) }
}
