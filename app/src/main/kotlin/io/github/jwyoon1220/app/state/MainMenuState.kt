package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.core.GameState
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.event.KeyEvent

class MainMenuState(private val ctx: GameContext) : GameState {

    private val menuItems = listOf("Play", "Edit Song", "Quit")
    private var selectedIndex = 0

    private val titleFont = Font("Arial", Font.BOLD, 72)
    private val menuFont  = Font("Arial", Font.PLAIN, 36)
    private val selFont   = Font("Arial", Font.BOLD, 42)

    override fun enter() {
        ctx.inputManager.clearEvents()
    }

    override fun exit() {}

    override fun update(deltaTime: Double) {}

    override fun render(g: Graphics2D) {
        val w = g.clipBounds?.width  ?: 1280
        val h = g.clipBounds?.height ?: 720

        g.color = Color(0, 0, 0, 180)
        g.fillRect(0, 0, w, h)

        // 타이틀
        g.font = titleFont
        g.color = Color(200, 160, 255)
        val title = "StelLane"
        val tfm = g.getFontMetrics(titleFont)
        g.drawString(title, (w - tfm.stringWidth(title)) / 2, h / 4)

        // 버전
        g.font = Font("Arial", Font.PLAIN, 16)
        g.color = Color(120, 120, 140)
        g.drawString("Rhythm Game", (w - tfm.stringWidth(title)) / 2 + 10, h / 4 + 28)

        // 메뉴 항목
        val startY = h / 2
        menuItems.forEachIndexed { i, item ->
            val selected = i == selectedIndex
            g.font  = if (selected) selFont else menuFont
            val fm  = g.getFontMetrics(g.font)
            g.color = if (selected) Color(255, 220, 80) else Color(200, 200, 200)
            g.drawString(item, (w - fm.stringWidth(item)) / 2, startY + i * 64)
        }

        // 조작 안내
        g.font  = Font("Arial", Font.PLAIN, 15)
        g.color = Color(100, 100, 120)
        val hint = "↑↓ Navigate   Enter Select"
        val hfm = g.getFontMetrics(g.font)
        g.drawString(hint, (w - hfm.stringWidth(hint)) / 2, h - 30)
    }

    override fun keyPressed(e: KeyEvent) {
        when (e.keyCode) {
            KeyEvent.VK_UP    -> selectedIndex = (selectedIndex - 1 + menuItems.size) % menuItems.size
            KeyEvent.VK_DOWN  -> selectedIndex = (selectedIndex + 1) % menuItems.size
            KeyEvent.VK_ENTER -> onSelect()
        }
    }

    private fun onSelect() {
        when (selectedIndex) {
            0 -> ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.PLAY))
            1 -> ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.EDIT))
            2 -> System.exit(0)
        }
    }
}
