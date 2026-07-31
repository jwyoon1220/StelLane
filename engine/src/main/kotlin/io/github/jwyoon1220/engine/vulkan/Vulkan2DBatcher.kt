package io.github.jwyoon1220.engine.vulkan

import io.github.jwyoon1220.engine.UnsafeMemory.UNSAFE
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
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

    // pushVertex는 프레임당 수천 번씩 불리는 가장 뜨거운 경로라(ByteBuffer.putFloat도, LWJGL의
    // MemoryUtil.memPutFloat도 JDK 21에서는 내부적으로 ScopedMemoryAccess 라이브니스 체크를 거침 —
    // 프로파일링에서 메인 스레드 CPU 샘플의 절반 이상이 여기였음) sun.misc.Unsafe로 직접 씁니다
    // (UnsafeMemory 참고). VMA persistently-mapped 버퍼는 주소가 안 바뀌므로 프레임-인-플라이트별
    // 주소를 한 번만 계산해둡니다.
    private val vertexAddrs: LongArray = LongArray(framesInFlight) {
        MemoryUtil.memAddress(checkNotNull(vertexBuffers[it].mappedData) { "버텍스 버퍼가 host-visible이 아닙니다" })
    }

    val transform = TransformStack()

    private var cmdBuf: VkCommandBuffer? = null
    private var mappedAddr: Long = 0L
    private var vertexCount = 0   // 이번 프레임에 지금까지 써넣은 총 정점 수
    private var baseVertex = 0    // 아직 draw하지 않은 구간의 시작 (flush 시 vertexCount로 갱신)
    private var boundDescriptorSet = -1L
    private var overflowWarned = false

    // letterbox 변환 (물리 픽셀 기준) — 시저 계산 + 블러 변경 시 푸시상수 재구성에 필요
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var fbW = 0
    private var fbH = 0
    private var designW = 0f
    private var designH = 0f
    private var currentFontBlur = 0f

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
        this.designW = designW; this.designH = designH
        mappedAddr = vertexAddrs[frameIndex]
        vertexCount = 0; baseVertex = 0; boundDescriptorSet = -1L; overflowWarned = false
        currentFontBlur = 0f
        transform.reset()
        currentClip = null; clipStack.clear()

        vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline)

        stackPush().use { stack ->
            val viewport = VkViewport.calloc(1, stack)
            viewport[0].x(0f).y(0f).width(fbWidth.toFloat()).height(fbHeight.toFloat()).minDepth(0f).maxDepth(1f)
            vkCmdSetViewport(cmdBuf, 0, viewport)

            setScissorRaw(stack, 0, 0, fbWidth, fbHeight)
            vkCmdBindVertexBuffers(cmdBuf, 0, stack.longs(vertexBuffers[frameIndex].buffer), stack.longs(0L))
        }
        pushProjectionConstants()
    }

    /** [x],[y] 좌표계/뷰포트가 바뀔 때(beginFrame) 또는 [setFontBlur]로 블러 값만 바뀔 때 호출합니다. */
    private fun pushProjectionConstants() {
        val cmd = cmdBuf ?: return
        stackPush().use { stack ->
            val pc = stack.mallocFloat(9)
            pc.put(0, offsetX); pc.put(1, offsetY)
            pc.put(2, designW * scale); pc.put(3, designH * scale)
            pc.put(4, designW); pc.put(5, designH)
            pc.put(6, fbW.toFloat()); pc.put(7, fbH.toFloat())
            pc.put(8, currentFontBlur)
            vkCmdPushConstants(cmd, pipeline.pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT, 0, pc)
        }
    }

    /**
     * SDF 텍스트의 흐림 반경(디자인 픽셀 단위, [io.github.jwyoon1220.engine.DrawContext.setFontBlur]에서 옴)을
     * 설정합니다. 다음 [flush] 전까지 그리는 모든 MODE_SDF_TEXT 정점에 적용됩니다 — 텍스처 바인딩처럼
     * 값이 바뀔 때만 먼저 flush()해 이전 값으로 그려야 할 정점들을 확정 짓습니다.
     */
    fun setFontBlur(blur: Float) {
        if (blur == currentFontBlur) return
        flush()
        currentFontBlur = blur
        pushProjectionConstants()
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
        if (mappedAddr == 0L) return
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

        val base = mappedAddr + vertexCount.toLong() * Vulkan2DVertex.STRIDE_BYTES
        UNSAFE.putFloat(base,      wx)
        UNSAFE.putFloat(base + 4,  wy)
        UNSAFE.putFloat(base + 8,  u)
        UNSAFE.putFloat(base + 12, v)
        UNSAFE.putFloat(base + 16, r)
        UNSAFE.putFloat(base + 20, g)
        UNSAFE.putFloat(base + 24, b)
        UNSAFE.putFloat(base + 28, a)
        UNSAFE.putFloat(base + 32, mode)
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

    /**
     * 축 정렬 사각형을 코너마다 다른 색으로 채웁니다([io.github.jwyoon1220.engine.GlQuadBatchRenderer.drawGradientRect]와
     * 동일한 용도 — PlayScene 노트처럼 계산된 그라디언트 공식이 아니라 이미 정해진 4개 색을 그대로 보간할 때 씁니다).
     */
    fun pushQuadGradient(
        x: Float, y: Float, w: Float, h: Float,
        cTL: FloatArray, cTR: FloatArray, cBR: FloatArray, cBL: FloatArray, mode: Float
    ) {
        val x1 = x + w; val y1 = y + h
        pushVertex(x,  y,  0f, 0f, cTL[0], cTL[1], cTL[2], cTL[3], mode)
        pushVertex(x1, y,  0f, 0f, cTR[0], cTR[1], cTR[2], cTR[3], mode)
        pushVertex(x1, y1, 0f, 0f, cBR[0], cBR[1], cBR[2], cBR[3], mode)
        pushVertex(x,  y,  0f, 0f, cTL[0], cTL[1], cTL[2], cTL[3], mode)
        pushVertex(x1, y1, 0f, 0f, cBR[0], cBR[1], cBR[2], cBR[3], mode)
        pushVertex(x,  y1, 0f, 0f, cBL[0], cBL[1], cBL[2], cBL[3], mode)
    }

    /**
     * 임의의 4개 코너(시계/반시계 순서 상관없이 사각형을 이루는 순서)로 쿼드를 채웁니다.
     * 축 정렬이 아닌 회전된 사각형(두꺼운 선분 등)에 씁니다. uv는 전부 (0,0) — 단색 전용.
     */
    fun pushQuadFromCorners(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
        r: Float, g: Float, b: Float, a: Float, mode: Float
    ) {
        pushVertex(x0, y0, 0f, 0f, r, g, b, a, mode)
        pushVertex(x1, y1, 0f, 0f, r, g, b, a, mode)
        pushVertex(x2, y2, 0f, 0f, r, g, b, a, mode)
        pushVertex(x0, y0, 0f, 0f, r, g, b, a, mode)
        pushVertex(x2, y2, 0f, 0f, r, g, b, a, mode)
        pushVertex(x3, y3, 0f, 0f, r, g, b, a, mode)
    }

    /**
     * 볼록 다각형을 중심점 팬 삼각분할로 채웁니다. [points]는 (x,y) 쌍이 순서대로 나열된 배열이고,
     * 둘레를 따라 인접한 점들이 이어져야 합니다(둥근 사각형/원/삼각형 등 — 오목한 다각형에는 안 맞음).
     */
    fun pushFan(
        cx: Float, cy: Float, points: FloatArray, pointCount: Int,
        r: Float, g: Float, b: Float, a: Float, mode: Float
    ) {
        for (i in 0 until pointCount) {
            val j = (i + 1) % pointCount
            pushVertex(cx, cy, 0f, 0f, r, g, b, a, mode)
            pushVertex(points[i * 2], points[i * 2 + 1], 0f, 0f, r, g, b, a, mode)
            pushVertex(points[j * 2], points[j * 2 + 1], 0f, 0f, r, g, b, a, mode)
        }
    }

    /**
     * 볼록 다각형을 팬 삼각분할로 채우되, 각 점의 색을 [colorAt]로 개별 계산합니다(그라디언트용).
     * GPU 래스터라이저가 삼각형 내부를 보간해주므로, 점마다 정확한 그라디언트 값을 넣으면
     * 셰이더 변경 없이 선형/방사형/박스 그라디언트를 전부 표현할 수 있습니다.
     */
    fun pushFanGradient(
        cx: Float, cy: Float, points: FloatArray, pointCount: Int,
        mode: Float, colorAt: (Float, Float) -> FloatArray
    ) {
        val cc = colorAt(cx, cy)
        val pc = Array(pointCount) { colorAt(points[it * 2], points[it * 2 + 1]) }
        for (i in 0 until pointCount) {
            val j = (i + 1) % pointCount
            pushVertex(cx, cy, 0f, 0f, cc[0], cc[1], cc[2], cc[3], mode)
            pushVertex(points[i * 2], points[i * 2 + 1], 0f, 0f, pc[i][0], pc[i][1], pc[i][2], pc[i][3], mode)
            pushVertex(points[j * 2], points[j * 2 + 1], 0f, 0f, pc[j][0], pc[j][1], pc[j][2], pc[j][3], mode)
        }
    }

    /**
     * (x,y,w,h) 영역을 [gridN]×[gridN] 격자로 잘게 나눠 채웁니다. 각 격자점의 색을 [colorAt]로
     * 계산해 정점 색으로 넣으면, 방사형/박스 그라디언트처럼 선형이 아닌 falloff도 조각별
     * bilinear 보간으로 근사할 수 있습니다(격자가 촘촘할수록 부드러움 — 그라디언트는 호출
     * 빈도가 낮아 이 정도 정점 수는 부담이 아닙니다).
     */
    fun pushGridGradient(x: Float, y: Float, w: Float, h: Float, gridN: Int, mode: Float, colorAt: (Float, Float) -> FloatArray) {
        val stepX = w / gridN; val stepY = h / gridN
        val colors = Array(gridN + 1) { row -> Array(gridN + 1) { col -> colorAt(x + col * stepX, y + row * stepY) } }
        for (row in 0 until gridN) {
            for (col in 0 until gridN) {
                val x0 = x + col * stepX; val y0 = y + row * stepY
                val x1 = x0 + stepX; val y1 = y0 + stepY
                val cTL = colors[row][col]; val cTR = colors[row][col + 1]
                val cBR = colors[row + 1][col + 1]; val cBL = colors[row + 1][col]
                pushVertex(x0, y0, 0f, 0f, cTL[0], cTL[1], cTL[2], cTL[3], mode)
                pushVertex(x1, y0, 0f, 0f, cTR[0], cTR[1], cTR[2], cTR[3], mode)
                pushVertex(x1, y1, 0f, 0f, cBR[0], cBR[1], cBR[2], cBR[3], mode)
                pushVertex(x0, y0, 0f, 0f, cTL[0], cTL[1], cTL[2], cTL[3], mode)
                pushVertex(x1, y1, 0f, 0f, cBR[0], cBR[1], cBR[2], cBR[3], mode)
                pushVertex(x0, y1, 0f, 0f, cBL[0], cBL[1], cBL[2], cBL[3], mode)
            }
        }
    }

    /** 두 점 사이를 [width] 두께의 사각형(두꺼운 선분)으로 채웁니다. */
    fun pushLineSegment(
        x1: Float, y1: Float, x2: Float, y2: Float, width: Float,
        r: Float, g: Float, b: Float, a: Float, mode: Float
    ) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return
        val hw = width / 2f
        val nx = -dy / len * hw
        val ny = dx / len * hw
        pushQuadFromCorners(
            x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny,
            r, g, b, a, mode
        )
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
        mappedAddr = 0L
    }

    fun destroy() {
        vertexBuffers.forEach { allocator.destroyBuffer(it) }
    }
}
