package io.github.jwyoon1220.engine


import org.lwjgl.nanovg.NanoVGGL3.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.slf4j.LoggerFactory

/**
 * OpenGL + NanoVG 기반 렌더러.
 * [init] 은 GLFWWindow 가 생성되고 OpenGL 컨텍스트가 현재 스레드에 bind 된 후에 호출하세요.
 */
class Renderer(
    private val window: GLFWWindow,
    private val stateManager: SceneRouter,
    private val videoBackground: VideoBackground
) {
    private val log = LoggerFactory.getLogger(Renderer::class.java)

    companion object {
        const val DESIGN_W = 1280
        const val DESIGN_H = 720
    }

    private var vg: Long = 0L
    lateinit var drawContext: DrawContext
        private set
    private val glQuadBatchRenderer = GlQuadBatchRenderer(DESIGN_W.toFloat(), DESIGN_H.toFloat())
    private val postProcessPass = PostProcessPass()

    /** 옵션: Main 에서 ImGuiManager 를 생성 후 주입합니다. null 이면 ImGui 패스 건너뜀. */
    var imGuiManager: ImGuiManager? = null

    // letterbox/pillarbox 계산 결과 (toLogical 에서도 사용)
    @Volatile private var scale   = 1f
    @Volatile private var offsetX = 0f
    @Volatile private var offsetY = 0f

    val renderScale:   Float get() = scale
    val renderOffsetX: Float get() = offsetX
    val renderOffsetY: Float get() = offsetY

    fun init() {
        vg = nvgCreate(NVG_ANTIALIAS or NVG_STENCIL_STROKES)
        check(vg != 0L) { "[Renderer] NanoVG 컨텍스트 생성 실패" }

        FontRegistry.loadAll(vg)
        videoBackground.initGLTexture()
        glQuadBatchRenderer.init()

        drawContext = DrawContext(vg, DESIGN_W, DESIGN_H)
        log.info("[Renderer] 초기화 완료 vg=0x{}", java.lang.Long.toHexString(vg))
    }

    fun destroy() {
        postProcessPass.destroy()
        glQuadBatchRenderer.destroy()
        if (vg != 0L) { nvgDelete(vg); vg = 0L }
    }

    /**
     * 매 프레임 호출합니다.
     * 순서: glClear → 비디오 업로드 → NVG 프레임 시작 → letterbox 변환 → State 렌더 → NVG 프레임 끝
     */
    fun renderFrame() {
        val fbW = window.framebufferWidth
        val fbH = window.framebufferHeight
        if (fbW <= 0 || fbH <= 0) return
        val current = stateManager.current

        // GL 후처리 효과 수집 (FBO 사용 여부 결정)
        val glEffects = (current as? GlEffectProvider)?.collectActiveGlEffects() ?: emptyList()
        val hasGlEffects = glEffects.isNotEmpty()

        // 1. 비디오 프레임 GL 텍스처 업로드 (새 프레임 있을 때만)
        videoBackground.uploadPendingFrame()

        // 2. 렌더 대상 설정 및 클리어
        if (hasGlEffects) {
            // 효과 활성화 — FBO-A에 캡처 (beginCapture 내부에서 클리어)
            postProcessPass.beginCapture(fbW, fbH)
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glViewport(0, 0, fbW, fbH)
            glClearColor(0f, 0f, 0f, 1f)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT or GL_STENCIL_BUFFER_BIT)
        }

        // 3. letterbox / pillarbox 계산 (논리 1280×720 → 물리 픽셀)
        val s  = minOf(fbW.toFloat() / DESIGN_W, fbH.toFloat() / DESIGN_H)
        val dw = (DESIGN_W * s).toInt()
        val dh = (DESIGN_H * s).toInt()
        val ox = (fbW - dw) / 2f
        val oy = (fbH - dh) / 2f
        scale   = s
        offsetX = ox
        offsetY = oy

        // 4. NanoVG 프레임 시작 (픽셀 비율 = 1; 이미 물리 픽셀 크기로 beginFrame)
        drawContext.beginFrame(fbW, fbH)

        // 5. 비디오 배경 렌더 (State 가 자체 배경을 처리하지 않는 경우)
        val rendersBg = current?.rendersBackground == true
        val videoNvgHandle = videoBackground.getNvgImageHandle(vg)
        if (!rendersBg && videoNvgHandle >= 0) {
            drawContext.drawNvgImage(videoNvgHandle, ox, oy, dw.toFloat(), dh.toFloat())
        }

        // 6. 논리 좌표(1280×720)로 변환 후 State 렌더
        drawContext.save()
        drawContext.translate(ox, oy)
        drawContext.scale(s, s)
        drawContext.setClip(0f, 0f, DESIGN_W.toFloat(), DESIGN_H.toFloat())
        stateManager.render(drawContext)
        drawContext.restore()

        // 7. NanoVG 프레임 끝
        drawContext.endFrame()

        // 8. 선택적 커스텀 OpenGL 패스 (State 구현 시)
        if (current is CustomGLRenderable && current.useCustomGlRenderer) {
            glQuadBatchRenderer.begin(
                framebufferWidth = fbW,
                framebufferHeight = fbH,
                offsetX = ox,
                offsetY = oy,
                drawW = dw.toFloat(),
                drawH = dh.toFloat()
            )
            current.renderCustomGl(glQuadBatchRenderer)
            glQuadBatchRenderer.end()
        }

        // 9. GL 후처리 효과 적용 — FBO 캡처 종료 후 화면에 출력
        if (hasGlEffects) {
            postProcessPass.endCapture()
            postProcessPass.apply(glEffects, fbW, fbH, (System.nanoTime() / 1_000_000_000f))
        }

        // 10. Dear ImGui 패스 (ImGuiRenderable 구현 State 에서만, 또는 빈 프레임)
        val imgui = imGuiManager
        if (imgui != null) {
            imgui.newFrame()
            if (current is ImGuiRenderable) current.renderImGui()
            imgui.render()
        }
    }

    /**
     * 물리 픽셀 좌표 → 논리(1280×720) 좌표로 변환합니다.
     * InputManager 에서 마우스 이벤트 역변환에 사용합니다.
     */
    fun toLogical(x: Double, y: Double): Pair<Float, Float> =
        Pair(((x - offsetX) / scale).toFloat(), ((y - offsetY) / scale).toFloat())
}
