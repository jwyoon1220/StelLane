package io.github.jwyoon1220.app.ui.ingame

import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.app.FontLoader
import java.awt.Color

class UIContextMenu(
    val x: Int,
    val y: Int,
    val items: List<ContextMenuItem>
) {
    data class ContextMenuItem(val label: String, val action: () -> Unit, val isSeparator: Boolean = false)

    private val itemHeight = 28
    private val width = 180
    private val height = items.size * itemHeight
    private var hoverIdx = -1

    private val font = FontLoader.regular(13f)

    fun render(g: DrawContext) {
        // 배경 그림자
        g.color = Color(0, 0, 0, 60)
        g.fillRoundRect(x + 4, y + 4, width, height, 8, 8)

        // 메인 배경
        g.color = Color(25, 20, 40, 245)
        g.fillRoundRect(x, y, width, height, 8, 8)
        g.color = Color(80, 60, 120, 180)
        g.drawRoundRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), 8f)

        items.forEachIndexed { i, item ->
            val iy = y + i * itemHeight
            if (item.isSeparator) {
                g.color = Color(60, 50, 90, 150)
                g.drawLine(x + 8, iy + itemHeight / 2, x + width - 8, iy + itemHeight / 2)
            } else {
                val isHovered = i == hoverIdx
                if (isHovered) {
                    g.color = Color(100, 70, 180, 150)
                    g.fillRoundRect(x + 4, iy + 2, width - 8, itemHeight - 4, 4, 4)
                }

                g.font = font
                g.color = if (isHovered) Color.WHITE else Color(200, 190, 230)
                g.drawString(item.label, x + 12, iy + itemHeight / 2 + 5)
            }
        }
    }

    fun onMouseMove(mx: Int, my: Int) {
        if (mx in x..(x + width) && my in y..(y + height)) {
            hoverIdx = (my - y) / itemHeight
        } else {
            hoverIdx = -1
        }
    }

    fun onMouseClick(mx: Int, my: Int): Boolean {
        if (mx in x..(x + width) && my in y..(y + height)) {
            val idx = (my - y) / itemHeight
            val item = items.getOrNull(idx)
            if (item != null && !item.isSeparator) {
                item.action()
                return true
            }
        }
        return false
    }
}
