package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.stb.STBTTFontinfo
import org.lwjgl.stb.STBTruetype.*
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkQueue
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

/** 아틀라스 안에서 글리프 하나의 위치/크기/메트릭 — 전부 [VulkanFontAtlas.REFERENCE_PX] 기준 픽셀값. */
class GlyphInfo(
    val u0: Float, val v0: Float, val u1: Float, val v1: Float,
    val width: Float, val height: Float,
    val xoff: Float, val yoff: Float,
    val advance: Float
) {
    companion object {
        /** 그릴 게 없는 글리프(공백 등) — advance만 유효. */
        fun empty(advance: Float) = GlyphInfo(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, advance)
    }
    val hasBitmap: Boolean get() = width > 0f && height > 0f
}

/**
 * 폰트 슬롯 하나의 SDF 아틀라스. [REFERENCE_PX] 크기로 딱 한 번만 굽고, 실제 그리기 크기는 쿼드
 * 크기만 스케일해서 재사용합니다(SDF라 확대해도 흐려지지 않음) — NanoVG처럼 호출마다 다른 크기를
 * 요청해도 아틀라스를 다시 구울 필요가 없습니다.
 *
 * ASCII(32~126)는 [prebakeAscii]로 미리 구워 흔한 케이스에서 프레임 중 히치가 없고, 그 밖의
 * 코드포인트(한글 등)는 처음 등장할 때 한 번만 굽습니다 — 그 순간에만 스테이징 버퍼+one-time
 * 커맨드 제출(`vkQueueWaitIdle` 포함) 비용이 들고, 이후로는 캐시에서 재사용해 프레임당 비용이
 * 0입니다. 매 프레임 새 글리프가 계속 나오는 극단적 상황이 아니라면 실용적인 트레이드오프입니다.
 */
class VulkanFontAtlas private constructor(
    private val device: VkDevice,
    private val allocator: VmaAllocator,
    private val commandPool: Long,
    private val queue: VkQueue,
    private val fontInfo: STBTTFontinfo,
    private val texture: VulkanTexture,
    val descriptorSet: Long
) {
    companion object {
        private val log = LoggerFactory.getLogger(VulkanFontAtlas::class.java)

        const val REFERENCE_PX = 48f
        private const val ATLAS_SIZE = 1024
        private const val PADDING = 4
        private const val ON_EDGE_VALUE = 180
        private const val PIXEL_DIST_SCALE = 32f

        fun create(
            device: VkDevice, allocator: VmaAllocator, commandPool: Long, queue: VkQueue,
            pipeline: Vulkan2DPipeline, ttfBytes: ByteBuffer
        ): VulkanFontAtlas {
            val fontInfo = STBTTFontinfo.create()
            check(stbtt_InitFont(fontInfo, ttfBytes)) { "stbtt_InitFont 실패" }

            // 아직 안 구운 영역이 잘못 샘플링되지 않도록 0(거리값 최솟값 = "전부 배경")으로 채워 시작.
            val blank = MemoryUtil.memCalloc(ATLAS_SIZE * ATLAS_SIZE)
            val texture = VulkanTexture.createFromR8(device, allocator, commandPool, queue, ATLAS_SIZE, ATLAS_SIZE, blank)
            MemoryUtil.memFree(blank)
            val descSet = pipeline.createTextureDescriptorSet(texture.imageView)

            return VulkanFontAtlas(device, allocator, commandPool, queue, fontInfo, texture, descSet)
        }
    }

    private val scale = stbtt_ScaleForPixelHeight(fontInfo, REFERENCE_PX)

    val ascent: Float
    val descent: Float
    val lineGap: Float

    init {
        stackPush().use { stack ->
            val a = stack.mallocInt(1); val d = stack.mallocInt(1); val lg = stack.mallocInt(1)
            stbtt_GetFontVMetrics(fontInfo, a, d, lg)
            ascent = a[0] * scale
            descent = d[0] * scale
            lineGap = lg[0] * scale
        }
    }

    private val glyphs = HashMap<Int, GlyphInfo>()
    private var penX = 0
    private var penY = 0
    private var rowHeight = 0
    private var atlasFull = false

    /** ASCII 출력 가능 범위를 미리 구워둡니다 — 영문/숫자 UI 텍스트에서 프레임 중 굽기를 없앱니다. */
    fun prebakeAscii() {
        for (c in 32..126) getOrBake(c)
    }

    fun getOrBake(codepoint: Int): GlyphInfo? {
        glyphs[codepoint]?.let { return it }
        return bake(codepoint)
    }

    private fun advanceWidthPx(codepoint: Int): Float = stackPush().use { stack ->
        val adv = stack.mallocInt(1); val lsb = stack.mallocInt(1)
        stbtt_GetCodepointHMetrics(fontInfo, codepoint, adv, lsb)
        adv[0] * scale
    }

    /**
     * 두 코드포인트 사이 커닝(자간 보정) 픽셀값. NanoVG/fontstash는 폰트에 kern 테이블이 있으면
     * 기본으로 적용하는데 Vulkan 쪽은 지금까지 이걸 빼먹어서, 글자 수가 많을수록(누적) OpenGL과
     * 점점 벌어지는 자간 drift가 생겼습니다(짧은 단어는 티 안 나고 긴 단어일수록 눈에 띔).
     */
    fun kernAdvancePx(prevCodepoint: Int, codepoint: Int): Float =
        stbtt_GetCodepointKernAdvance(fontInfo, prevCodepoint, codepoint) * scale

    private fun bake(codepoint: Int): GlyphInfo? {
        val adv = advanceWidthPx(codepoint)
        if (atlasFull) return GlyphInfo.empty(adv).also { glyphs[codepoint] = it }

        return stackPush().use { stack ->
            val w = stack.mallocInt(1); val h = stack.mallocInt(1)
            val xoff = stack.mallocInt(1); val yoff = stack.mallocInt(1)
            val bitmap = stbtt_GetCodepointSDF(
                fontInfo, scale, codepoint, PADDING, ON_EDGE_VALUE.toByte(), PIXEL_DIST_SCALE, w, h, xoff, yoff
            )
            if (bitmap == null) {
                val info = GlyphInfo.empty(adv)
                glyphs[codepoint] = info
                return@use info
            }
            try {
                val bw = w[0]; val bh = h[0]
                if (penX + bw > ATLAS_SIZE) { penX = 0; penY += rowHeight; rowHeight = 0 }
                if (penY + bh > ATLAS_SIZE) {
                    log.warn("[VulkanFontAtlas] 아틀라스({}×{})가 가득 찼습니다 — 이후 새 글리프는 빈 칸으로 표시됩니다", ATLAS_SIZE, ATLAS_SIZE)
                    atlasFull = true
                    val info = GlyphInfo.empty(adv)
                    glyphs[codepoint] = info
                    return@use info
                }

                texture.uploadRegion(device, allocator, commandPool, queue, penX, penY, bw, bh, 1, bitmap)

                val info = GlyphInfo(
                    u0 = penX.toFloat() / ATLAS_SIZE, v0 = penY.toFloat() / ATLAS_SIZE,
                    u1 = (penX + bw).toFloat() / ATLAS_SIZE, v1 = (penY + bh).toFloat() / ATLAS_SIZE,
                    width = bw.toFloat(), height = bh.toFloat(),
                    xoff = xoff[0].toFloat(), yoff = yoff[0].toFloat(),
                    advance = adv
                )
                glyphs[codepoint] = info
                penX += bw + PADDING
                rowHeight = maxOf(rowHeight, bh + PADDING)
                info
            } finally {
                stbtt_FreeSDF(bitmap)
            }
        }
    }

    fun destroy() {
        texture.destroy(device, allocator)
    }
}
