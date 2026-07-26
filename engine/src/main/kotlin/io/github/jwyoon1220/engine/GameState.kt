package io.github.jwyoon1220.engine

import io.github.jwyoon1220.engine.ecs.Scene

/** ECS 씬 라우터 — 씬 전환을 담당합니다. */
class SceneRouter {
    private var _current: Scene? = null
    val current: Scene? get() = _current

    fun navigate(scene: Scene) {
        _current?.exit()
        _current = scene
        scene.enter()
    }

    internal fun update(deltaTime: Double) = _current?.update(deltaTime)
    internal fun render(g: DrawContext) = _current?.render(g)
}
