package io.github.jwyoon1220.app.ui.ingame

import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

abstract class UIComponent(var x: Int, var y: Int, var width: Int, var height: Int) {
    var isVisible = true
    var isFocused = false

    abstract fun render(g: Graphics2D, parentX: Int, parentY: Int)
    open fun update(deltaTime: Double) {}
    open fun onMousePressed(mx: Int, my: Int, button: Int): Boolean = false
    open fun onMouseReleased(mx: Int, my: Int, button: Int): Boolean = false
    open fun onMouseDragged(mx: Int, my: Int): Boolean = false
    open fun onKeyPressed(e: KeyEvent): Boolean = false
    open fun onKeyTyped(e: KeyEvent): Boolean = false

    fun contains(mx: Int, my: Int, parentX: Int, parentY: Int): Boolean {
        return mx in (parentX + x)..(parentX + x + width) && my in (parentY + y)..(parentY + y + height)
    }
}

class UILabel(
    x: Int, y: Int, var text: String,
    var color: Color = Color.WHITE,
    var font: Font = io.github.jwyoon1220.app.FontLoader.regular.deriveFont(12f)
) : UIComponent(x, y, 0, 0) {
    override fun render(g: Graphics2D, parentX: Int, parentY: Int) {
        g.font = font
        g.color = color
        g.drawString(text, parentX + x, parentY + y + g.fontMetrics.ascent)
    }
}

class UIButton(
    x: Int, y: Int, width: Int, height: Int,
    var text: String,
    var onClick: () -> Unit
) : UIComponent(x, y, width, height) {
    private var isHovered = false
    private var isPressed = false

    override fun render(g: Graphics2D, parentX: Int, parentY: Int) {
        g.color = when {
            isPressed -> Color(80, 80, 120)
            isHovered -> Color(100, 100, 150)
            else -> Color(60, 60, 90)
        }
        g.fillRoundRect(parentX + x, parentY + y, width, height, 8, 8)
        g.color = Color.WHITE
        g.font = io.github.jwyoon1220.app.FontLoader.bold.deriveFont(12f)
        val fm = g.fontMetrics
        val tx = parentX + x + (width - fm.stringWidth(text)) / 2
        val ty = parentY + y + (height - fm.height) / 2 + fm.ascent
        g.drawString(text, tx, ty)
    }

    override fun onMousePressed(mx: Int, my: Int, button: Int): Boolean {
        if (contains(mx, my, 0, 0)) { // 좌표계는 Window 내부이므로 parentX,Y는 Window 측에서 보정 후 전달됨
            isPressed = true
            return true
        }
        return false
    }

    override fun onMouseReleased(mx: Int, my: Int, button: Int): Boolean {
        if (isPressed && contains(mx, my, 0, 0)) {
            onClick()
        }
        isPressed = false
        return false
    }
}

class UITextField(
    x: Int, y: Int, width: Int, height: Int,
    var text: String = ""
) : UIComponent(x, y, width, height) {
    override fun render(g: Graphics2D, parentX: Int, parentY: Int) {
        g.color = if (isFocused) Color(40, 40, 60) else Color(20, 20, 30)
        g.fillRect(parentX + x, parentY + y, width, height)
        g.color = if (isFocused) Color(150, 150, 255) else Color(100, 100, 150)
        g.drawRect(parentX + x, parentY + y, width, height)

        g.color = Color.WHITE
        g.font = io.github.jwyoon1220.app.FontLoader.regular.deriveFont(12f)
        val fm = g.fontMetrics
        g.drawString(text + (if (isFocused && System.currentTimeMillis() % 1000 < 500) "|" else ""), parentX + x + 4, parentY + y + fm.ascent + (height - fm.height) / 2)
    }

    override fun onKeyPressed(e: KeyEvent): Boolean {
        if (!isFocused) return false
        if (e.keyCode == KeyEvent.VK_BACK_SPACE && text.isNotEmpty()) {
            text = text.dropLast(1)
        }
        return true
    }

    override fun onKeyTyped(e: KeyEvent): Boolean {
        if (!isFocused) return false
        val c = e.keyChar
        if (c >= ' ' && c != KeyEvent.CHAR_UNDEFINED && c.code != 127) {
            text += c
        }
        return true
    }
}

open class UIWindow(
    val id: String,
    var title: String,
    x: Int, y: Int, width: Int, height: Int
) : UIComponent(x, y, width, height) {
    val components = mutableListOf<UIComponent>()
    private val titleHeight = 24
    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    override fun render(g: Graphics2D, parentX: Int, parentY: Int) {
        if (!isVisible) return
        val px = parentX + x
        val py = parentY + y
        
        // 배경
        g.color = Color(30, 30, 45, 230)
        g.fillRoundRect(px, py, width, height, 10, 10)
        
        // 타이틀바
        g.color = Color(50, 50, 75, 250)
        g.fillRoundRect(px, py, width, titleHeight, 10, 10)
        g.fillRect(px, py + titleHeight - 5, width, 5)
        
        // 외곽선
        g.color = Color(80, 80, 120)
        g.drawRoundRect(px, py, width, height, 10, 10)
        
        g.color = Color.WHITE
        g.font = io.github.jwyoon1220.app.FontLoader.bold.deriveFont(12f)
        g.drawString(title, px + 8, py + 16)
        
        // 닫기 버튼 (가상)
        g.color = Color(200, 80, 80)
        g.fillRoundRect(px + width - 20, py + 4, 16, 16, 4, 4)
        
        for (comp in components) {
            if (comp.isVisible) comp.render(g, px, py + titleHeight)
        }
    }

    override fun onMousePressed(mx: Int, my: Int, button: Int): Boolean {
        if (!isVisible) return false
        val px = x; val py = y
        // 닫기 버튼
        if (mx in (px + width - 20)..(px + width - 4) && my in (py + 4)..(py + 20)) {
            UIManager.removeWindow(id)
            return true
        }
        // 타이틀바 드래그
        if (mx in px..(px + width) && my in py..(py + titleHeight)) {
            dragging = true
            dragOffsetX = mx - x
            dragOffsetY = my - y
            return true
        }
        
        // 자식 컴포넌트 이벤트 전달
        val relX = mx - px
        val relY = my - py - titleHeight
        var handled = false
        for (comp in components.asReversed()) {
            if (comp.isVisible && comp.contains(mx, my, px, py + titleHeight)) {
                // Focus 관리
                components.filterIsInstance<UITextField>().forEach { it.isFocused = false }
                if (comp is UITextField) comp.isFocused = true
                
                if (comp.onMousePressed(relX, relY, button)) handled = true
                break
            }
        }
        if (!handled) {
            components.filterIsInstance<UITextField>().forEach { it.isFocused = false }
        }
        return true // 창 클릭 시 이벤트 흡수
    }

    override fun onMouseDragged(mx: Int, my: Int): Boolean {
        if (!isVisible) return false
        if (dragging) {
            x = mx - dragOffsetX
            y = my - dragOffsetY
            return true
        }
        val px = x; val py = y
        val relX = mx - px
        val relY = my - py - titleHeight
        for (comp in components.asReversed()) {
            if (comp.isVisible) {
                if (comp.onMouseDragged(relX, relY)) return true
            }
        }
        return false
    }

    override fun onMouseReleased(mx: Int, my: Int, button: Int): Boolean {
        if (!isVisible) return false
        dragging = false
        val px = x; val py = y
        val relX = mx - px
        val relY = my - py - titleHeight
        for (comp in components.asReversed()) {
            if (comp.isVisible) {
                if (comp.onMouseReleased(relX, relY, button)) return true
            }
        }
        return false
    }

    override fun onKeyPressed(e: KeyEvent): Boolean {
        for (comp in components) {
            if (comp.isVisible && comp.isFocused && comp.onKeyPressed(e)) return true
        }
        return false
    }

    override fun onKeyTyped(e: KeyEvent): Boolean {
        for (comp in components) {
            if (comp.isVisible && comp.isFocused && comp.onKeyTyped(e)) return true
        }
        return false
    }
}

object UIManager {
    private val windows = mutableListOf<UIWindow>()

    fun open(window: UIWindow) {
        windows.removeIf { it.id == window.id }
        windows.add(window)
    }

    fun removeWindow(id: String) {
        windows.removeIf { it.id == id }
    }

    fun closeAll() {
        windows.clear()
    }

    fun render(g: Graphics2D) {
        for (window in windows) {
            window.render(g, 0, 0)
        }
    }

    fun onMousePressed(e: MouseEvent): Boolean {
        for (window in windows.asReversed()) {
            if (window.contains(e.x, e.y, 0, 0)) {
                windows.remove(window)
                windows.add(window) // Bring to front
                window.onMousePressed(e.x, e.y, e.button)
                return true
            }
        }
        return false
    }

    fun onMouseDragged(e: MouseEvent): Boolean {
        val front = windows.lastOrNull() ?: return false
        return front.onMouseDragged(e.x, e.y)
    }

    fun onMouseReleased(e: MouseEvent): Boolean {
        val front = windows.lastOrNull() ?: return false
        return front.onMouseReleased(e.x, e.y, e.button)
    }

    fun onKeyPressed(e: KeyEvent): Boolean {
        val front = windows.lastOrNull() ?: return false
        return front.onKeyPressed(e)
    }

    fun onKeyTyped(e: KeyEvent): Boolean {
        val front = windows.lastOrNull() ?: return false
        return front.onKeyTyped(e)
    }
}
