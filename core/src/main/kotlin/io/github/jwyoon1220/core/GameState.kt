package io.github.jwyoon1220.core

import java.awt.Graphics2D
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

// 게임의 화면 단위(메인, 곡 선택, 플레이, 에디터 등)를 추상화합니다.
interface GameState {
    fun enter()
    fun update(deltaTime: Double)
    fun render(g: Graphics2D)
    fun exit()

    fun keyPressed(e: KeyEvent)  {}
    fun keyReleased(e: KeyEvent) {}
    fun mousePressed(e: MouseEvent)  {}
    fun mouseClicked(e: MouseEvent)  {}
    fun mouseReleased(e: MouseEvent) {}
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
    
    fun render(g: Graphics2D) {
        currentState?.render(g)
    }
}
