package io.github.jwyoon1220.app.render

import io.github.jwyoon1220.engine.GlQuadBatchRenderer

object GameRenderer {
    private var activeRenderer: Renderer? = null

    fun registerRenderer(renderer: Renderer) {
        activeRenderer = renderer
    }

    fun getRenderer(): Renderer? {
        return activeRenderer
    }
}
