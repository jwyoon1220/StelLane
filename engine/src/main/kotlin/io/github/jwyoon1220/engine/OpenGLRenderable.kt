package io.github.jwyoon1220.engine

/**
 * NanoVG 렌더 이후 OpenGL 커스텀 패스를 사용할 Scene 계약.
 */
interface OpenGLRenderable {
    val useOpenGLRenderer: Boolean get() = false
    fun renderOpenGL(renderer: GlQuadBatchRenderer) {}
}
