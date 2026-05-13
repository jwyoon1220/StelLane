package io.github.jwyoon1220.engine

/**
 * NanoVG 렌더 이후 OpenGL 커스텀 패스를 사용할 State 계약.
 */
interface CustomGLRenderable {
    val useCustomGlRenderer: Boolean get() = false
    fun renderCustomGl(renderer: GlQuadBatchRenderer) {}
}

