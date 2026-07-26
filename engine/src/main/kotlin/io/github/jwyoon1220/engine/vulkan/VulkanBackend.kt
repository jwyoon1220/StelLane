package io.github.jwyoon1220.engine.vulkan

import io.github.jwyoon1220.engine.RenderApi
import io.github.jwyoon1220.engine.render.RenderCommand
import io.github.jwyoon1220.engine.render.RendererBackend
import io.github.jwyoon1220.engine.render.RendererContext
import org.slf4j.LoggerFactory

/**
 * Vulkan 렌더러 백엔드 — [RendererBackend] 계약은 구현하지만, [submit]으로 들어오는
 * [RenderCommand](NanoVG 2D 드로잉 커맨드)는 아직 실행하지 않습니다. 이 단계의 목표는
 * "검증 레이어가 깨끗한 상태로 스왑체인에 클리어 컬러를 프레젠트"까지입니다 — 2D 배치
 * 파이프라인(RenderCommand → Vulkan 드로우콜)은 다음 단계 작업입니다.
 *
 * ## 중요 — 아직 게임 루프에 꽂혀 있지 않음
 * [io.github.jwyoon1220.engine.Renderer]는 여전히 OpenGL 전용 오케스트레이션
 * (glBindFramebuffer/glClear/GL 후처리/GlQuadBatchRenderer 등)을 프레임마다 직접 수행하므로,
 * 이 백엔드를 지금 당장 `RendererFactory`에 등록해 기존 게임 루프에 꽂아도 정상 동작하지
 * 않습니다. 지금은 [io.github.jwyoon1220.engine.RenderApi.VULKAN]으로 만든 창과 함께
 * 독립적으로 구동하는 용도이며(예: [VulkanContextSmokeTest]), Renderer를 백엔드에 무관하게
 * 만드는 작업이 끝난 뒤에 실제로 앱에 연결합니다.
 */
class VulkanBackend(private val enableValidation: Boolean = true) : RendererBackend {
    private val log = LoggerFactory.getLogger(VulkanBackend::class.java)

    override val id: String = "vulkan"

    private lateinit var vk: VulkanContext
    private var pendingFbW = 0
    private var pendingFbH = 0
    private var warnedUnsupportedCommands = false

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

    override fun beginFrame(framebufferWidth: Int, framebufferHeight: Int, scale: Float, offsetX: Float, offsetY: Float) {
        pendingFbW = framebufferWidth
        pendingFbH = framebufferHeight
    }

    override fun submit(commands: List<RenderCommand>) {
        if (commands.isNotEmpty() && !warnedUnsupportedCommands) {
            warnedUnsupportedCommands = true
            log.warn("[Vulkan] RenderCommand 실행은 아직 구현되지 않았습니다(2D 배치 파이프라인 후속 작업) — {}개 커맨드 무시", commands.size)
        }
    }

    override fun endFrame() {
        vk.drawFrame(pendingFbW, pendingFbH)
    }

    /** 창 리사이즈 콜백에서 호출하면 다음 [endFrame] 전에 스왑체인을 미리 맞춰둘 수 있습니다(선택 사항 — endFrame도 out-of-date 시 자동 재생성함). */
    fun notifyResize(fbWidth: Int, fbHeight: Int) = vk.recreateSwapchain(fbWidth, fbHeight)

    override fun destroy() {
        if (::vk.isInitialized) vk.destroy()
    }
}
