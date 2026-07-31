package io.github.jwyoon1220.engine.render

import io.github.jwyoon1220.engine.GLFWWindow
import io.github.jwyoon1220.engine.ImGuiManager
import io.github.jwyoon1220.engine.VideoBackground
import io.github.jwyoon1220.engine.ecs.Scene

/**
 * 렌더러 컨텍스트 — RendererBackend 초기화에 필요한 환경 정보를 담습니다.
 *
 * @param window        GLFW 창 핸들
 * @param videoBackground VLC 비디오 배경 (nullable; 비디오가 없으면 null)
 * @param designWidth   논리 해상도 너비 (기본 1280)
 * @param designHeight  논리 해상도 높이 (기본 720)
 */
data class RendererContext(
    val window: GLFWWindow,
    val videoBackground: VideoBackground? = null,
    val designWidth: Int = 1280,
    val designHeight: Int = 720
)

/**
 * 렌더러 백엔드 인터페이스 — 실제 GPU 그리기를 담당하는 플러그 가능한 구현체.
 *
 * [io.github.jwyoon1220.engine.Renderer]는 이 인터페이스 뒤에서 무슨 일이 일어나는지 전혀 모릅니다 —
 * 프레임버퍼 크기와 letterbox/pillarbox 변환값(scale/offset)만 계산해서 [renderFrame]에 넘길 뿐,
 * 클리어/비디오 배경 합성/RenderCommand 실행/후처리/UI 오버레이는 전부 백엔드 책임입니다.
 * 그래서 `org.lwjgl.opengl.*` 같은 그래픽스 API 심볼은 Renderer가 아니라 각 백엔드 구현체에만
 * 등장해야 합니다.
 *
 * ## 구현 예
 * - [io.github.jwyoon1220.engine.render.NanoVGBackend]: NanoVG + OpenGL + GL 후처리 + ImGui
 * - [io.github.jwyoon1220.engine.vulkan.VulkanBackend]: Vulkan (RenderCommand 실행은 아직 미구현)
 *
 * ## 스레드 요구사항
 * 모든 메서드는 **GLFW 메인 스레드**에서 호출해야 합니다.
 */
interface RendererBackend {

    /** 백엔드 고유 식별자 (예: "nanovg", "vulkan"). [RendererFactory] 등록 키와 일치해야 합니다. */
    val id: String

    /**
     * Dear ImGui 관리자 (선택). 앱 초기화 순서상 [init] 이후에 설정되는 경우가 많아 별도 프로퍼티로
     * 노출합니다. ImGui 렌더링을 지원하지 않는 백엔드(현재 Vulkan)는 이 값을 무시해도 됩니다.
     */
    var imGuiManager: ImGuiManager?

    /**
     * 그래픽스 컨텍스트가 활성화된 상태에서 백엔드를 초기화합니다.
     * GLFW 창 생성 직후, 게임 루프 시작 전에 호출됩니다.
     *
     * @param ctx 렌더러 컨텍스트 (창 핸들, 비디오 배경 등)
     */
    fun init(ctx: RendererContext)

    /**
     * 한 프레임을 통째로 그립니다 — 클리어, 비디오 배경 합성, [scene]의 RenderCommand 실행,
     * 백엔드별 커스텀 패스(OpenGL 후처리/커스텀 GL 렌더러 등), UI 오버레이(ImGui)까지 이 호출
     * 하나에서 전부 처리합니다. [GLFWWindow.swapBuffers]는 게임 루프가 별도로 호출합니다.
     *
     * @param scene             이번 프레임의 현재 씬. 아직 씬이 없으면 null.
     * @param framebufferWidth  실제 프레임버퍼 너비 (HiDPI 스케일 적용)
     * @param framebufferHeight 실제 프레임버퍼 높이
     * @param scale             framebuffer / 논리 해상도(designWidth/Height) 비율
     * @param offsetX           letterbox/pillarbox X 오프셋 (물리 픽셀)
     * @param offsetY           letterbox/pillarbox Y 오프셋 (물리 픽셀)
     */
    fun renderFrame(
        scene: Scene?,
        framebufferWidth: Int,
        framebufferHeight: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    )

    /**
     * 백엔드를 해제합니다. 창이 닫힐 때 호출됩니다.
     * GPU 객체, 그래픽스 컨텍스트, 텍스처 등을 정리하세요.
     */
    fun destroy()

    /**
     * 디버그 전용 — 다음 [renderFrame] 결과를 원시 프레임버퍼 픽셀 그대로 PNG로 저장합니다
     * (OS 스크린샷/색 관리를 거치지 않아 백엔드 간 픽셀 단위 비교에 씁니다). 지원하지 않는
     * 백엔드는 기본 구현(무시)을 그대로 씁니다.
     */
    fun debugCaptureFrame(path: String) {}

    /**
     * VSync를 켜고 끕니다. OpenGL은 [io.github.jwyoon1220.engine.GLFWWindow.setVSync]가
     * glfwSwapInterval로 직접 처리하므로 기본 구현(무시)이면 충분 — Vulkan은 스왑체인 present
     * mode를 다시 골라야 해서 오버라이드합니다([io.github.jwyoon1220.engine.vulkan.VulkanBackend]).
     */
    fun setVSync(enabled: Boolean) {}
}
