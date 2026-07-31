package io.github.jwyoon1220.engine

/**
 * 메인 DrawContext 렌더 이후 저수준 커스텀 배치 패스를 쓸 Scene 계약(예: PlayScene 노트 렌더링).
 * 백엔드마다 [QuadBatchRenderer] 구현이 다릅니다(OpenGL: [GlQuadBatchRenderer], Vulkan:
 * [io.github.jwyoon1220.engine.vulkan.VulkanQuadBatchRenderer]).
 */
interface OpenGLRenderable {
    val useOpenGLRenderer: Boolean get() = false
    fun renderOpenGL(renderer: QuadBatchRenderer) {}
}
