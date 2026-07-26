package io.github.jwyoon1220.engine

import io.github.jwyoon1220.engine.render.NanoVGBackend
import io.github.jwyoon1220.engine.render.RendererBackend
import io.github.jwyoon1220.engine.render.RendererContext
import io.github.jwyoon1220.engine.render.RendererFactory
import org.slf4j.LoggerFactory

/**
 * 프레임 오케스트레이터 — **어떤 그래픽스 API도 직접 호출하지 않습니다.**
 * 매 프레임 프레임버퍼 크기를 읽고 letterbox/pillarbox 변환값(scale/offset)을 계산해
 * [RendererBackend.renderFrame]에 넘길 뿐, 클리어/드로우콜/후처리/UI 오버레이는 전부
 * 백엔드([io.github.jwyoon1220.engine.render.NanoVGBackend] 또는
 * [io.github.jwyoon1220.engine.vulkan.VulkanBackend]) 책임입니다. 그래서 이 클래스에는
 * `org.lwjgl.opengl.*`/`org.lwjgl.vulkan.*` 심볼이 전혀 등장하지 않습니다 — 백엔드를 바꿔도
 * Renderer는 손댈 필요가 없습니다.
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

    private lateinit var backend: RendererBackend

    /** 사용할 백엔드 ID. [init] 호출 전에 바꿔야 적용됩니다. 기본값은 기존 OpenGL/NanoVG 파이프라인. */
    var backendId: String = "nanovg"

    /** 옵션: Main 에서 ImGuiManager 를 생성 후 주입합니다. null 이면 백엔드가 ImGui 패스를 건너뜁니다. */
    var imGuiManager: ImGuiManager?
        get() = if (::backend.isInitialized) backend.imGuiManager else null
        set(value) { backend.imGuiManager = value }

    // letterbox/pillarbox 계산 결과 (toLogical 에서도 사용)
    @Volatile private var scale   = 1f
    @Volatile private var offsetX = 0f
    @Volatile private var offsetY = 0f

    val renderScale:   Float get() = scale
    val renderOffsetX: Float get() = offsetX
    val renderOffsetY: Float get() = offsetY

    fun init() {
        RendererFactory.register("nanovg") { NanoVGBackend() }
        val ctx = RendererContext(window, videoBackground, DESIGN_W, DESIGN_H)
        backend = RendererFactory.create(backendId, ctx)
        backend.init(ctx)

        log.info("[Renderer] 초기화 완료 (backend={})", backend.id)
    }

    fun destroy() {
        backend.destroy()
    }

    /**
     * 매 프레임 호출합니다. 프레임버퍼 크기를 읽고 letterbox 변환값을 계산해 백엔드에 위임합니다.
     */
    fun renderFrame() {
        val fbW = window.framebufferWidth
        val fbH = window.framebufferHeight
        if (fbW <= 0 || fbH <= 0) return

        // letterbox / pillarbox 계산 (논리 1280×720 → 물리 픽셀) — 백엔드와 무관한 순수 수학
        val s  = minOf(fbW.toFloat() / DESIGN_W, fbH.toFloat() / DESIGN_H)
        val dw = (DESIGN_W * s).toInt()
        val dh = (DESIGN_H * s).toInt()
        val ox = (fbW - dw) / 2f
        val oy = (fbH - dh) / 2f
        scale   = s
        offsetX = ox
        offsetY = oy

        backend.renderFrame(stateManager.current, fbW, fbH, s, ox, oy)
    }

    /**
     * 물리 픽셀 좌표 → 논리(1280×720) 좌표로 변환합니다.
     * InputManager 에서 마우스 이벤트 역변환에 사용합니다.
     */
    fun toLogical(x: Double, y: Double): Pair<Float, Float> =
        Pair(((x - offsetX) / scale).toFloat(), ((y - offsetY) / scale).toFloat())
}
