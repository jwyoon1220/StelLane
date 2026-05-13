package io.github.jwyoon1220.engine

/**
 * ImGui 오버레이 UI를 렌더링하는 GameState가 구현하는 인터페이스.
 * [Renderer.renderFrame]은 게임 렌더 이후 이 인터페이스를 구현한 상태에 대해
 * ImGui newFrame / renderImGui / render 를 호출합니다.
 */
interface ImGuiRenderable {
    fun renderImGui()
}
