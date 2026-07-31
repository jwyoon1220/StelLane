package io.github.jwyoon1220.engine.vulkan

import io.github.jwyoon1220.engine.ImGuiManager
import io.github.jwyoon1220.engine.OpenGLRenderable
import io.github.jwyoon1220.engine.RenderApi
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.render.RendererBackend
import io.github.jwyoon1220.engine.render.RendererContext
import io.github.jwyoon1220.engine.render.executeOnDrawContext
import org.slf4j.LoggerFactory

/**
 * Vulkan 렌더러 백엔드 — [Scene.gatherRenderCommands]가 반환하는 [RenderCommand]를
 * [VulkanDrawContext]로 직접 실행합니다(`LegacyDrawContext`가 대부분이라 사실상 각 씬의
 * `renderContents(g: DrawContext)`가 그대로 Vulkan 위에서 실행됩니다).
 *
 * ## known gap
 * - ImGui: `imgui-java-lwjgl3`가 OpenGL 전용이라 Vulkan에서는 지원하지 않습니다(설정되면 경고 후 무시).
 * - `drawNvgImage`/`drawNvgImageTransformed`(비디오 배경 외의 원시 NanoVG 핸들 호출자는 없음 — 비디오
 *   배경 자체는 [VulkanVideoTexture]로 별도 구현됨): 경고 후 무시.
 *
 * [io.github.jwyoon1220.engine.Renderer]는 이제 백엔드 무관 오케스트레이터이므로, 이 백엔드를
 * `RendererFactory`에 등록해 `Renderer.backendId = "vulkan"`으로 앱에 바로 연결할 수 있습니다
 * (단, [io.github.jwyoon1220.engine.RenderApi.VULKAN]으로 만든 창이어야 함 — [init]에서 확인).
 */
class VulkanBackend(private val enableValidation: Boolean = true) : RendererBackend {
    private val log = LoggerFactory.getLogger(VulkanBackend::class.java)

    override val id: String = "vulkan"

    /** ImGui는 OpenGL 전용 바인딩(imgui-java-lwjgl3)에 의존 — Vulkan 백엔드는 현재 지원하지 않고 무시합니다. */
    override var imGuiManager: ImGuiManager? = null

    private lateinit var vk: VulkanContext
    private lateinit var drawContext: VulkanDrawContext
    private lateinit var quadBatchRenderer: VulkanQuadBatchRenderer
    private var videoBackground: io.github.jwyoon1220.engine.VideoBackground? = null
    private var designW = 1280f
    private var designH = 720f
    private var warnedImGuiIgnored = false

    override fun init(ctx: RendererContext) {
        check(ctx.window.api == RenderApi.VULKAN) {
            "VulkanBackend는 RenderApi.VULKAN으로 생성된 GLFWWindow가 필요합니다 (실제: ${ctx.window.api})"
        }
        designW = ctx.designWidth.toFloat()
        designH = ctx.designHeight.toFloat()
        videoBackground = ctx.videoBackground
        vk = VulkanContext.create(
            appName = "StelLane",
            windowHandle = ctx.window.handle,
            fbWidth = ctx.window.framebufferWidth,
            fbHeight = ctx.window.framebufferHeight,
            enableValidation = enableValidation,
            vsync = ctx.window.vSync
        )
        drawContext = VulkanDrawContext(vk.batcher, vk.whiteTextureDescriptorSet, vk.fontSystem, vk.imageCache, ctx.designWidth, ctx.designHeight)
        quadBatchRenderer = VulkanQuadBatchRenderer(vk.batcher)
    }

    override fun renderFrame(
        scene: Scene?,
        framebufferWidth: Int,
        framebufferHeight: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        val commands = scene?.gatherRenderCommands() ?: emptyList()
        if (imGuiManager != null && !warnedImGuiIgnored) {
            warnedImGuiIgnored = true
            log.warn("[Vulkan] ImGui는 아직 지원하지 않습니다 — 무시합니다")
        }

        vk.drawFrame(framebufferWidth, framebufferHeight, scale, offsetX, offsetY, designW, designH, videoBackground) { batcher ->
            // NvgDrawContext.beginFrame과 동일하게, 씬이 이전 프레임의 색/알파/스트로크 상태를
            // 물려받지 않도록 매 프레임 그리기 상태를 기본값으로 되돌립니다.
            drawContext.beginFrame(framebufferWidth, framebufferHeight)

            // NanoVGBackend의 4단계와 동일한 순서 — 씬이 자체 배경을 처리하지 않으면 비디오 배경을 먼저 그립니다.
            val rendersBg = scene?.rendersBackground == true
            val videoDescSet = vk.videoTexture.currentDescriptorSet()
            if (!rendersBg && videoDescSet != null) {
                batcher.bindTexture(videoDescSet)
                batcher.pushQuad(0f, 0f, designW, designH, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f, Vulkan2DVertex.MODE_IMAGE)
                batcher.bindTexture(vk.whiteTextureDescriptorSet)
            }

            for (cmd in commands) cmd.executeOnDrawContext(drawContext)

            // NanoVGBackend의 7단계와 동일 — Scene이 OpenGLRenderable을 구현한 경우(예: PlayScene 노트 렌더러)
            if (scene is OpenGLRenderable && scene.useOpenGLRenderer) {
                scene.renderOpenGL(quadBatchRenderer)
            }
        }
    }

    /** 창 리사이즈 콜백에서 호출하면 다음 [renderFrame] 전에 스왑체인을 미리 맞춰둘 수 있습니다(선택 사항 — renderFrame도 out-of-date 시 자동 재생성함). */
    fun notifyResize(fbWidth: Int, fbHeight: Int) = vk.recreateSwapchain(fbWidth, fbHeight)

    /** 디버그 전용 — 다음 프레임을 PNG로 저장합니다. */
    override fun debugCaptureFrame(path: String) = vk.requestFrameCapture(path)

    /** GLFWWindow.setVSync는 GL 전용이라 Vulkan은 대신 이걸로 스왑체인 present mode를 바꿉니다. */
    override fun setVSync(enabled: Boolean) = vk.setVSync(enabled)

    override fun destroy() {
        if (::vk.isInitialized) vk.destroy()
    }
}
