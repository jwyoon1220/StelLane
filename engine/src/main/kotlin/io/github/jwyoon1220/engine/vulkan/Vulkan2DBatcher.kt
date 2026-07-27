package io.github.jwyoon1220.engine.vulkan

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkRect2D
import org.lwjgl.vulkan.VkViewport
import org.slf4j.LoggerFactory

/**
 * 2D 배치 렌더러 — [Vulkan2DVertex] 포맷 정점을 채워 넣다가 텍스처나 시저(클립)가 바뀔 때만
 * `vkCmdDraw`를 발행합니다. GlQuadBatchRenderer(OpenGL)와 같은 전략이지만, 프레임 안에서는
 * CPU 쓰기 커서를 절대 0으로 되감지 않습니다 — 이 프레임의 모든 vkCmdDraw는 커맨드 버퍼가 실제로
 * GPU에 제출된 뒤에야 실행되므로, 같은 프레임 안에서 이미 flush한 앞쪽 구간을 다시 덮어쓰면
 * 그 구간을 참조하는 draw call이 나중에 잘못된 데이터를 읽게 됩니다. 매 [beginFrame]에서만
 * (해당 frame-in-flight의 펜스를 이미 기다린 뒤이므로) 안전하게 커서를 0으로 되돌립니다.
 *
 * 프레임-인-플라이트 개수만큼 정점 버퍼를 하나씩 소유합니다(각각 VMA host-visible + persistently mapped).
 */
class Vulkan2DBatcher(
    private val pipeline: Vulkan2DPipeline,
    private val allocator: VmaAllocator,
    framesInFlight: Int,
    private val maxVertices: Int = 65536
) {
    private val log = LoggerFactory.getLogger(Vulkan2DBatcher::class.java)

    private val vertexBuffers: Array<VmaBuffer> = Array(framesInFlight) {
        allocator.createBuffer(
            maxVertices.toLong() * Vulkan2DVertex.STRIDE_BYTES,
            VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
            hostVisible = true
        )
    }

    val transform = TransformStack()

    private var cmdBuf: VkCommandBuffer? = null
    private var mapped: java.nio.ByteBuffer? = null
    private var vertexCount = 0   // 이번 프레임에 지금까지 써넣은 총 정점 수
    private var baseVertex = 0    // 아직 draw하지 않은 구간의 시작 (flush 시 vertexCount로 갱신)
    private var boundDescriptorSet = -1L
    private var overflowWarned = false

    // letterbox 변환 (물리 픽셀 기준) — 시저 계산에 필요
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var fbW = 0
    private var fbH = 0

    private data class ClipRect(val x: Float, val y: Float, val w: Float, val h: Float)
    private var currentClip: ClipRect? = null
    private val clipStack = ArrayDeque<ClipRect?>()

    fun beginFrame(
        cmdBuf: VkCommandBuffer, frameIndex: Int,
        fbWidth: Int, fbHeight: Int, scale: Float, offsetX: Float, offsetY: Float,
        designW: Float, designH: Float
    ) {
        this.cmdBuf = cmdBuf
        this.fbW = fbWidth; this.fbH = fbHeight
        this.scale = scale; this.offsetX = offsetX; this.offsetY = offsetY
        mapped = vertexBuffers[frameIndex].mappedData
        vertexCount = 0; baseVertex = 0; boundDescriptorSet = -1L; overflowWarned = false
        transform.reset()
        currentClip = null; clipStack.clear()

        vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline)

        stackPush().use { stack ->
            val viewport = VkViewport.calloc(1, stack)
            viewport[0].x(0f).y(0f).width(fbWidth.toFloat()).height(fbHeight.toFloat()).minDepth(0f).maxDepth(1f)
            vkCmdSetViewport(cmdBuf, 0, viewport)

            setScissorRaw(stack, 0, 0, fbWidth, fbHeight)

            val pc = stack.mallocFloat(8)
            pc.put(0, offsetX); pc.put(1, offsetY)
            pc.put(2, designW * scale); pc.put(3, designH * scale)
            pc.put(4, designW); pc.put(5, designH)
            pc.put(6, fbWidth.toFloat()); pc.put(7, fbHeight.toFloat())
            vkCmdPushConstants(cmdBuf, pipeline.pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pc)

            vkCmdBindVertexBuffers(cmdBuf, 0, stack.longs(vertexBuffers[frameIndex].buffer), stack.longs(0L))
        }
    }

    // ── 변환 스택 위임 (DrawContext가 그대로 호출) ────────────────────────────
    fun save() { transform.save(); clipStack.addLast(currentClip) }
    fun restore() {
        transform.restore()
        currentClip = clipStack.removeLastOrNull() ?: null
        applyClip()
    }

    // ── 클립 ────────────────────────────────────────────────────────────────
    fun setClip(x: Float, y: Float, w: Float, h: Float) {
        currentClip = ClipRect(x, y, w, h)
        applyClip()
    }

    fun resetClip() {
        currentClip = null
        applyClip()
    }

    private fun applyClip() {
        flush() // 시저를 바꾸기 전, 이전 시저로 그려야 할 정점들을 먼저 draw
        val cmd = cmdBuf ?: return
        val clip = currentClip
        stackPush().use { stack ->
            if (clip == null) {
                setScissorRaw(stack, 0, 0, fbW, fbH)
            } else {
                val px = (offsetX + clip.x * scale).toInt().coerceIn(0, fbW)
                val py = (offsetY + clip.y * scale).toInt().coerceIn(0, fbH)
                val pw = (clip.w * scale).toInt().coerceIn(0, fbW - px)
                val ph = (clip.h * scale).toInt().coerceIn(0, fbH - py)
                setScissorRaw(stack, px, py, pw, ph)
            }
        }
    }

    private fun setScissorRaw(stack: org.lwjgl.system.MemoryStack, x: Int, y: Int, w: Int, h: Int) {
        val cmd = cmdBuf ?: return
        val scissor = VkRect2D.calloc(1, stack)
        val rect = scissor[0]
        rect.offset().x(x).y(y)
        rect.extent().width(w).height(h)
        vkCmdSetScissor(cmd, 0, scissor)
    }

    // ── 텍스처 바인딩 ───────────────────────────────────────────────────────
    fun bindTexture(descriptorSet: Long) {
        if (descriptorSet == boundDescriptorSet) return
        flush()
        val cmd = cmdBuf ?: return
        stackPush().use { stack ->
            vkCmdBindDescriptorSets(
                cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipelineLayout,
                0, stack.longs(descriptorSet), null
            )
        }
        boundDescriptorSet = descriptorSet
    }

    // ── 정점 기록 ───────────────────────────────────────────────────────────
    /** [x],[y]는 현재 변환 스택이 적용되기 전의 로컬(디자인) 좌표입니다. */
    fun pushVertex(x: Float, y: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float, mode: Float) {
        val buf = mapped ?: return
        if (vertexCount >= maxVertices) {
            if (!overflowWarned) {
                overflowWarned = true
                log.warn("[Vulkan2DBatcher] 정점 버퍼({}개) 초과 — 이번 프레임의 나머지 그리기를 건너뜁니다", maxVertices)
            }
            return
        }
        val t = transform.current
        val wx = t.transformX(x, y)
        val wy = t.transformY(x, y)

        val base = vertexCount * Vulkan2DVertex.STRIDE_BYTES
        buf.putFloat(base,      wx)
        buf.putFloat(base + 4,  wy)
        buf.putFloat(base + 8,  u)
        buf.putFloat(base + 12, v)
        buf.putFloat(base + 16, r)
        buf.putFloat(base + 20, g)
        buf.putFloat(base + 24, b)
        buf.putFloat(base + 28, a)
        buf.putFloat(base + 32, mode)
        vertexCount++
    }

    /** 사각형 하나(삼각형 2개, 정점 6개)를 로컬 좌표 기준으로 채웁니다. */
    fun pushQuad(
        x: Float, y: Float, w: Float, h: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
        r: Float, g: Float, b: Float, a: Float, mode: Float
    ) {
        val x1 = x + w; val y1 = y + h
        pushVertex(x,  y,  u0, v0, r, g, b, a, mode)
        pushVertex(x1, y,  u1, v0, r, g, b, a, mode)
        pushVertex(x1, y1, u1, v1, r, g, b, a, mode)
        pushVertex(x,  y,  u0, v0, r, g, b, a, mode)
        pushVertex(x1, y1, u1, v1, r, g, b, a, mode)
        pushVertex(x,  y1, u0, v1, r, g, b, a, mode)
    }

    // ── flush / 종료 ────────────────────────────────────────────────────────
    fun flush() {
        val cmd = cmdBuf ?: return
        val count = vertexCount - baseVertex
        if (count <= 0) return
        vkCmdDraw(cmd, count, 1, baseVertex, 0)
        baseVertex = vertexCount
    }

    fun endFrame() {
        flush()
        cmdBuf = null
        mapped = null
    }

    fun destroy() {
        vertexBuffers.forEach { allocator.destroyBuffer(it) }
    }
}
