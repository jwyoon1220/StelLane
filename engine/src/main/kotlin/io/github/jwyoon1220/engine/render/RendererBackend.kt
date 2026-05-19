package io.github.jwyoon1220.engine.render

import io.github.jwyoon1220.engine.GLFWWindow
import io.github.jwyoon1220.engine.VideoBackground

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
 * ## 구현 예
 * - `NanoVGBackend`: 기존 엔진 렌더링 파이프라인 (NanoVG + OpenGL + ImGui)
 * - `DebugBackend`: 테스트용 no-op 구현
 *
 * ## 스레드 요구사항
 * 모든 메서드는 **GLFW 메인 스레드**에서 호출해야 합니다.
 * `GL.createCapabilities()` 이후 [init]이 최초로 호출됩니다.
 */
interface RendererBackend {

    /** 백엔드 고유 식별자 (예: "nanovg", "debug"). [RendererFactory] 등록 키와 일치해야 합니다. */
    val id: String

    /**
     * OpenGL 컨텍스트가 활성화된 상태에서 백엔드를 초기화합니다.
     * GLFW 창 생성 직후, 게임 루프 시작 전에 호출됩니다.
     *
     * @param ctx 렌더러 컨텍스트 (창 핸들, 비디오 배경 등)
     */
    fun init(ctx: RendererContext)

    /**
     * 새 프레임을 시작합니다.
     *
     * @param framebufferWidth  실제 프레임버퍼 너비 (HiDPI 스케일 적용)
     * @param framebufferHeight 실제 프레임버퍼 높이
     * @param scale             framebuffer / window 비율
     * @param offsetX           letterbox/pillarbox X 오프셋
     * @param offsetY           letterbox/pillarbox Y 오프셋
     */
    fun beginFrame(
        framebufferWidth: Int,
        framebufferHeight: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    )

    /**
     * [RenderCommand] 목록을 GPU에 제출합니다.
     * [beginFrame] 이후, [endFrame] 이전에 호출합니다.
     *
     * @param commands 이번 프레임의 렌더 커맨드 목록 (순서 보장)
     */
    fun submit(commands: List<RenderCommand>)

    /**
     * 프레임을 완료합니다 (NanoVG endFrame, ImGui render 등).
     * [GLFWWindow.swapBuffers]는 게임 루프가 별도로 호출합니다.
     */
    fun endFrame()

    /**
     * 백엔드를 해제합니다. 창이 닫힐 때 호출됩니다.
     * OpenGL 객체, NanoVG 컨텍스트, 텍스처 등을 정리하세요.
     */
    fun destroy()
}
