package io.github.jwyoon1220.engine.render

import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.FontRegistry
import org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS
import org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES
import org.lwjgl.nanovg.NanoVGGL3.nvgCreate
import org.lwjgl.nanovg.NanoVGGL3.nvgDelete
import org.slf4j.LoggerFactory

/**
 * 기본 [RendererBackend] 구현 — NanoVG + OpenGL 기반 2D 드로잉으로 [RenderCommand]를 실행합니다.
 *
 * [RendererFactory]에 `"nanovg"` 키로 등록되며, [io.github.jwyoon1220.engine.Renderer]가
 * 이 백엔드를 통해 씬의 렌더 커맨드를 제출합니다. letterbox/pillarbox 변환은 [beginFrame]에
 * 전달된 scale/offset을 기준으로 이 백엔드가 직접 적용합니다.
 */
class NanoVGBackend : RendererBackend {
    private val log = LoggerFactory.getLogger(NanoVGBackend::class.java)

    override val id: String = "nanovg"

    private var vg: Long = 0L

    /** 논리 좌표(1280×720)로 그리기 위한 [DrawContext]. 비디오 배경 등 커맨드 외 직접 드로잉에도 사용됩니다. */
    lateinit var drawContext: DrawContext
        private set

    /** NanoVG 컨텍스트 핸들. [io.github.jwyoon1220.engine.VideoBackground.getNvgImageHandle] 등에 필요합니다. */
    val nvgHandle: Long get() = vg

    override fun init(ctx: RendererContext) {
        vg = nvgCreate(NVG_ANTIALIAS or NVG_STENCIL_STROKES)
        check(vg != 0L) { "[NanoVGBackend] NanoVG 컨텍스트 생성 실패" }
        FontRegistry.loadAll(vg)
        drawContext = DrawContext(vg, ctx.designWidth, ctx.designHeight)
        log.info("[NanoVGBackend] 초기화 완료 vg=0x{}", java.lang.Long.toHexString(vg))
    }

    override fun beginFrame(
        framebufferWidth: Int,
        framebufferHeight: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        drawContext.beginFrame(framebufferWidth, framebufferHeight)
        // letterbox/pillarbox 변환: 논리 좌표(0,0,designW,designH) → 물리 프레임버퍼 좌표
        drawContext.save()
        drawContext.translate(offsetX, offsetY)
        drawContext.scale(scale, scale)
        drawContext.setClip(0f, 0f, drawContext.width.toFloat(), drawContext.height.toFloat())
    }

    override fun submit(commands: List<RenderCommand>) {
        for (cmd in commands) cmd.executeOnDrawContext(drawContext)
    }

    override fun endFrame() {
        drawContext.restore()
        drawContext.endFrame()
    }

    override fun destroy() {
        if (vg != 0L) {
            nvgDelete(vg)
            vg = 0L
        }
    }
}
