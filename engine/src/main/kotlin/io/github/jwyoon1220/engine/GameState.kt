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

// 화면 전환을 관리하는 매니저
class StateManager {
    var currentState: GameState? = null
        private set

    fun changeState(newState: GameState) {
        currentState?.exit()
        currentState = newState
        currentState?.enter()
    }

    fun update(deltaTime: Double) {
        currentState?.update(deltaTime)
    }

    fun render(g: DrawContext) {
        currentState?.render(g)
    }
}
