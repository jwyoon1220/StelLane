package io.github.jwyoon1220.engine

// 게임의 화면 단위(메인, 곡 선택, 플레이, 에디터 등)를 추상화합니다.
interface GameState {
    fun enter()
    fun update(deltaTime: Double)
    fun render(g: DrawContext)
    fun exit()

    fun keyPressed (key: Int, mods: Int) {}
    fun keyReleased(key: Int, mods: Int) {}
    /** GLFW character callback — Unicode 코드포인트 */
    fun keyTyped   (codepoint: Int)      {}
    fun mousePressed (x: Float, y: Float, button: Int, mods: Int) {}
    fun mouseClicked (x: Float, y: Float, button: Int, mods: Int) {}
    fun mouseReleased(x: Float, y: Float, button: Int, mods: Int) {}
    fun mouseDragged (x: Float, y: Float, button: Int)            {}
    fun mouseScrolled(dy: Double) {}

    /** State가 배경(비디오 등)을 자체적으로 그릴지 여부. true이면 Renderer가 기본 배경을 그리지 않습니다. */
    val rendersBackground: Boolean get() = false
}

/** ECS 전용 씬 라우터 — StateManager를 대체합니다. GameState 전환을 담당합니다. */
class SceneRouter {
    private var _current: GameState? = null
    val current: GameState? get() = _current

    fun navigate(scene: GameState) {
        _current?.exit()
        _current = scene
        scene.enter()
    }

    internal fun update(deltaTime: Double) = _current?.update(deltaTime)
    internal fun render(g: DrawContext) = _current?.render(g)
}

@Deprecated("Use SceneRouter", ReplaceWith("SceneRouter"))
typealias StateManager = SceneRouter
