package io.github.jwyoon1220.engine.render

import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.FontRegistry
import io.github.jwyoon1220.engine.GlEffectProvider
import io.github.jwyoon1220.engine.GlQuadBatchRenderer
import io.github.jwyoon1220.engine.ImGuiManager
import io.github.jwyoon1220.engine.ImGuiRenderable
import io.github.jwyoon1220.engine.OpenGLRenderable
import io.github.jwyoon1220.engine.PostProcessPass
import io.github.jwyoon1220.engine.VideoBackground
import io.github.jwyoon1220.engine.ecs.Scene
import org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS
import org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES
import org.lwjgl.nanovg.NanoVGGL3.nvgCreate
import org.lwjgl.nanovg.NanoVGGL3.nvgDelete
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.slf4j.LoggerFactory

/**
 * 기본 [RendererBackend] 구현 — NanoVG + OpenGL 기반 2D 드로잉으로 [io.github.jwyoon1220.engine.ecs.Scene]을
 * 렌더링합니다. 기존에 [io.github.jwyoon1220.engine.Renderer]가 직접 하던 GL 클리어/비디오 배경 합성/
 * GL 후처리/커스텀 GL 노트 렌더러/ImGui 오케스트레이션을 전부 이 클래스가 소유합니다 — Renderer는
 * letterbox 변환값만 계산해서 [renderFrame]에 넘길 뿐입니다.
 *
 * [RendererFactory]에 `"nanovg"` 키로 등록됩니다.
 */
class NanoVGBackend : RendererBackend {
    private val log = LoggerFactory.getLogger(NanoVGBackend::class.java)

    override val id: String = "nanovg"
    override var imGuiManager: ImGuiManager? = null

    private var vg: Long = 0L

    /** 논리 좌표(1280×720)로 그리기 위한 [DrawContext]. */
    private lateinit var drawContext: DrawContext
    private lateinit var videoBackground: VideoBackground
    private var designW = 0f
    private var designH = 0f

    private val postProcessPass = PostProcessPass()
    private lateinit var glQuadBatchRenderer: GlQuadBatchRenderer

    override fun init(ctx: RendererContext) {
        vg = nvgCreate(NVG_ANTIALIAS or NVG_STENCIL_STROKES)
        check(vg != 0L) { "[NanoVGBackend] NanoVG 컨텍스트 생성 실패" }
        FontRegistry.loadAll(vg)
        drawContext = DrawContext(vg, ctx.designWidth, ctx.designHeight)
        videoBackground = checkNotNull(ctx.videoBackground) { "[NanoVGBackend] videoBackground가 필요합니다" }
        videoBackground.initGLTexture() // GL 텍스처 생성 — 없으면 getNvgImageHandle()이 항상 -1을 반환해 배경이 그려지지 않음
        designW = ctx.designWidth.toFloat()
        designH = ctx.designHeight.toFloat()

        glQuadBatchRenderer = GlQuadBatchRenderer(designW, designH)
        glQuadBatchRenderer.init()

        log.info("[NanoVGBackend] 초기화 완료 vg=0x{}", java.lang.Long.toHexString(vg))
    }

    override fun renderFrame(
        scene: Scene?,
        framebufferWidth: Int,
        framebufferHeight: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        val fbW = framebufferWidth
        val fbH = framebufferHeight

        // GL 후처리 효과 수집 (FBO 사용 여부 결정)
        val glEffects = (scene as? GlEffectProvider)?.collectActiveGlEffects() ?: emptyList()
        val hasGlEffects = glEffects.isNotEmpty()

        // 1. 비디오 프레임 GL 텍스처 업로드 (새 프레임 있을 때만)
        videoBackground.uploadPendingFrame()

        // 2. 렌더 대상 설정 및 클리어
        if (hasGlEffects) {
            postProcessPass.beginCapture(fbW, fbH) // 효과 활성화 — FBO-A에 캡처 (내부에서 클리어)
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glViewport(0, 0, fbW, fbH)
            glClearColor(0f, 0f, 0f, 1f)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT or GL_STENCIL_BUFFER_BIT)
        }

        // 3. NanoVG 프레임 시작 — letterbox 변환(논리 좌표 → 물리 픽셀) 적용
        drawContext.beginFrame(fbW, fbH)
        drawContext.save()
        drawContext.translate(offsetX, offsetY)
        drawContext.scale(scale, scale)
        drawContext.setClip(0f, 0f, drawContext.width.toFloat(), drawContext.height.toFloat())

        // 4. 비디오 배경 렌더 (Scene이 자체 배경을 처리하지 않는 경우)
        val rendersBg = scene?.rendersBackground == true
        val videoNvgHandle = videoBackground.getNvgImageHandle(vg)
        if (!rendersBg && videoNvgHandle >= 0) {
            drawContext.drawNvgImage(videoNvgHandle, 0f, 0f, designW, designH)
        }

        // 5. Scene 렌더 — RenderCommand 모아서 실행
        if (scene != null) {
            for (cmd in scene.gatherRenderCommands()) cmd.executeOnDrawContext(drawContext)
        }

        // 6. NanoVG 프레임 종료 (변환 복원)
        drawContext.restore()
        drawContext.endFrame()

        // 7. 선택적 커스텀 OpenGL 패스 (Scene이 OpenGLRenderable을 구현한 경우 — 예: PlayScene 노트 렌더러)
        if (scene is OpenGLRenderable && scene.useOpenGLRenderer) {
            // 기존 Renderer와 동일하게 정수로 truncate 후 Float 변환 (letterbox 픽셀 경계와 일치시킴)
            val dw = (designW * scale).toInt()
            val dh = (designH * scale).toInt()
            glQuadBatchRenderer.begin(fbW, fbH, offsetX, offsetY, dw.toFloat(), dh.toFloat())
            scene.renderOpenGL(glQuadBatchRenderer)
            glQuadBatchRenderer.end()
        }

        // 8. GL 후처리 효과 적용 — FBO 캡처 종료 후 화면에 출력
        if (hasGlEffects) {
            postProcessPass.endCapture()
            postProcessPass.apply(glEffects, fbW, fbH, (System.nanoTime() / 1_000_000_000f))
        }

        // 9. Dear ImGui 패스 (ImGuiRenderable 구현 Scene에서만, 또는 빈 프레임)
        val imgui = imGuiManager
        if (imgui != null) {
            imgui.newFrame()
            if (scene is ImGuiRenderable) scene.renderImGui()
            imgui.render()
        }
    }

    override fun destroy() {
        if (::glQuadBatchRenderer.isInitialized) glQuadBatchRenderer.destroy()
        postProcessPass.destroy()
        if (vg != 0L) {
            nvgDelete(vg)
            vg = 0L
        }
    }
}
