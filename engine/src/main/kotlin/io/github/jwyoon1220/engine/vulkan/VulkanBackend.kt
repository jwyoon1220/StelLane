package io.github.jwyoon1220.engine.vulkan

import io.github.jwyoon1220.engine.ImGuiManager
import io.github.jwyoon1220.engine.RenderApi
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.render.RendererBackend
import io.github.jwyoon1220.engine.render.RendererContext
import org.slf4j.LoggerFactory

/**
 * Vulkan 렌더러 백엔드 — [RendererBackend] 계약은 구현하지만, [Scene.gatherRenderCommands]가
 * 반환하는 NanoVG 2D 드로잉 커맨드는 아직 실행하지 않습니다. 이 단계의 목표는 "검증 레이어가
 * 깨끗한 상태로 스왑체인에 클리어 컬러를 프레젠트"까지입니다 — 2D 배치 파이프라인
 * (RenderCommand → Vulkan 드로우콜)과 ImGui(Vulkan 백엔드)는 다음 단계 작업입니다.
 *
 * [io.github.jwyoon1220.engine.Renderer]는 이제 백엔드 무관 오케스트레이터이므로, 이 백엔드를
 * `RendererFactory`에 등록해 `Renderer.backendId = "vulkan"`으로 앱에 바로 연결할 수 있습니다
 * (단, [io.github.jwyoon1220.engine.RenderApi.VULKAN]으로 만든 창이어야 함 — [init]에서 확인).
 * 다만 화면에 나오는 건 아직 클리어 컬러뿐입니다.
 */
class VulkanBackend(private val enableValidation: Boolean = true) : RendererBackend {
    private val log = LoggerFactory.getLogger(VulkanBackend::class.java)

    override val id: String = "vulkan"

    /** ImGui는 OpenGL 전용 바인딩(imgui-java-lwjgl3)에 의존 — Vulkan 백엔드는 현재 지원하지 않고 무시합니다. */
    override var imGuiManager: ImGuiManager? = null

    private lateinit var vk: VulkanContext
    private var warnedUnsupportedCommands = false
    private var warnedImGuiIgnored = false

    override fun init(ctx: RendererContext) {
        check(ctx.window.api == RenderApi.VULKAN) {
            "VulkanBackend는 RenderApi.VULKAN으로 생성된 GLFWWindow가 필요합니다 (실제: ${ctx.window.api})"
        }
        vk = VulkanContext.create(
            appName = "StelLane",
            windowHandle = ctx.window.handle,
            fbWidth = ctx.window.framebufferWidth,
            fbHeight = ctx.window.framebufferHeight,
            enableValidation = enableValidation
        )
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
        if (commands.isNotEmpty() && !warnedUnsupportedCommands) {
            warnedUnsupportedCommands = true
            log.warn("[Vulkan] RenderCommand 실행은 아직 구현되지 않았습니다(2D 배치 파이프라인 후속 작업) — {}개 커맨드 무시", commands.size)
        }
        if (imGuiManager != null && !warnedImGuiIgnored) {
            warnedImGuiIgnored = true
            log.warn("[Vulkan] ImGui는 아직 지원하지 않습니다 — 무시합니다")
        }

        vk.drawFrame(framebufferWidth, framebufferHeight)
    }

    /** 창 리사이즈 콜백에서 호출하면 다음 [renderFrame] 전에 스왑체인을 미리 맞춰둘 수 있습니다(선택 사항 — renderFrame도 out-of-date 시 자동 재생성함). */
    fun notifyResize(fbWidth: Int, fbHeight: Int) = vk.recreateSwapchain(fbWidth, fbHeight)

    override fun destroy() {
        if (::vk.isInitialized) vk.destroy()
    }
}
