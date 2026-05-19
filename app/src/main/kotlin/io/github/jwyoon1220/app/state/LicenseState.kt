package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.GameState
import io.github.jwyoon1220.engine.Keys
import java.awt.Color

class LicenseState(private val ctx: GameContext) : GameState {

    private val lines: List<String> by lazy {
        runCatching {
            LicenseState::class.java.classLoader
                .getResourceAsStream("open-source-license")
                ?.bufferedReader()
                ?.readLines()
        }.getOrNull() ?: listOf("(라이선스 파일을 불러올 수 없습니다.)")
    }

    private var scrollOffset = 0

    private val LINE_H   = 17
    private val PAD_TOP  = 62
    private val PAD_BOT  = 44
    private val PAD_LEFT = 28

    private val titleFont = FontLoader.semiBold(20f)
    private val bodyFont  = FontLoader.regular(12f)
    private val hintFont  = FontLoader.light(11f)

    // 스크롤 드래그
    private var draggingScrollbar = false
    private var dragStartY        = 0f
    private var dragStartOffset   = 0

    override fun enter() { scrollOffset = 0; ctx.inputManager.clearEvents() }
    override fun exit()  {}
    override fun update(deltaTime: Double) {}

    override fun render(g: DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height

        // ── 배경 ─────────────────────────────────────────────────────────────
        g.color = Color(8, 6, 18)
        g.fillRect(0, 0, w, h)

        // ── 헤더 ─────────────────────────────────────────────────────────────
        g.fillLinearGradient(
            0f, 0f, w.toFloat(), PAD_TOP.toFloat(),
            0f, 0f, 0f, PAD_TOP.toFloat(),
            Color(24, 14, 52, 240), Color(10, 6, 24, 240)
        )
        g.color = Color(50, 28, 100, 140)
        g.drawLine(0, PAD_TOP, w, PAD_TOP)

        g.font  = titleFont
        g.color = Color(180, 140, 255)
        g.drawString("오픈소스 라이선스", PAD_LEFT.toFloat(), 38f)

        g.font  = hintFont
        g.color = Color(80, 65, 110)
        g.drawStringRight("↑↓ / PgUp·PgDn: 스크롤   Esc: 뒤로", (w - PAD_LEFT).toFloat(), 38f)

        // ── 본문 ──────────────────────────────────────────────────────────────
        val visibleLines = (h - PAD_TOP - PAD_BOT) / LINE_H
        val maxScroll    = maxOf(0, lines.size - visibleLines)
        scrollOffset     = scrollOffset.coerceIn(0, maxScroll)

        g.font = bodyFont

        g.scoped {
            setClip(0, PAD_TOP, w - 12, h - PAD_TOP - PAD_BOT)

            for (i in 0 until visibleLines) {
                val lineIdx = scrollOffset + i
                if (lineIdx >= lines.size) break
                val line  = lines[lineIdx]
                val lineY = (PAD_TOP + (i + 1) * LINE_H).toFloat()

                g.color = when {
                    line.startsWith("===") || line.startsWith("---") -> Color(50, 35, 80, 160)
                    line.startsWith("Applies to") || line.startsWith("Component") -> Color(140, 195, 245)
                    line.startsWith("  -") -> Color(165, 158, 195)
                    line.isBlank() -> Color(0, 0, 0, 0)
                    else -> Color(185, 178, 210)
                }

                if (!line.isBlank()) {
                    g.drawString(line, PAD_LEFT.toFloat(), lineY)
                } else {
                    g.color = Color(30, 22, 55, 80)
                    g.drawLine(PAD_LEFT, lineY.toInt(), w - 24, lineY.toInt())
                }
            }
        }

        // ── 스크롤바 ──────────────────────────────────────────────────────────
        if (lines.size > visibleLines) {
            val barX   = w - 8
            val barY   = PAD_TOP + 4
            val barH   = h - PAD_TOP - PAD_BOT - 8
            val thumbH = maxOf(24, barH * visibleLines / lines.size)
            val thumbY = barY + (barH - thumbH) * scrollOffset / maxOf(1, maxScroll)

            g.color = Color(22, 16, 40)
            g.fillRoundRect(barX.toFloat(), barY.toFloat(), 6f, barH.toFloat(), 3f)
            g.color = Color(90, 55, 150, 180)
            g.fillRoundRect(barX.toFloat(), thumbY.toFloat(), 6f, thumbH.toFloat(), 3f)
        }

        // ── 하단 상태바 ───────────────────────────────────────────────────────
        g.color = Color(15, 10, 32, 220)
        g.fillRect(0, h - PAD_BOT, w, PAD_BOT)
        g.color = Color(40, 28, 70, 120)
        g.drawLine(0, h - PAD_BOT, w, h - PAD_BOT)

        g.font  = hintFont
        g.color = Color(60, 50, 85)
        g.drawString(
            "${scrollOffset + 1} – ${(scrollOffset + visibleLines).coerceAtMost(lines.size)} / ${lines.size} lines",
            PAD_LEFT.toFloat(), (h - 16).toFloat()
        )

        // 진행률 바
        val progress = if (maxScroll > 0) scrollOffset.toFloat() / maxScroll else 0f
        g.color = Color(35, 22, 70)
        g.fillRect(0, h - 4, w, 4)
        g.color = Color(120, 70, 220, 180)
        g.fillRect(0, h - 4, (w * progress).toInt().coerceAtLeast(4), 4)
    }

    override fun keyPressed(key: Int, mods: Int) {
        when (key) {
            Keys.UP        -> scrollOffset = maxOf(0, scrollOffset - 1)
            Keys.DOWN      -> scrollOffset++
            Keys.PAGE_UP   -> scrollOffset = maxOf(0, scrollOffset - 30)
            Keys.PAGE_DOWN -> scrollOffset += 30
            Keys.HOME      -> scrollOffset = 0
            Keys.END       -> scrollOffset = lines.size
            Keys.ESCAPE    -> ctx.stateManager.changeState(MainMenuState(ctx))
        }
    }

    override fun mouseScrolled(dy: Double) {
        scrollOffset = if (dy < 0) scrollOffset + 3 else maxOf(0, scrollOffset - 3)
    }

    override fun mousePressed(x: Float, y: Float, button: Int, mods: Int) {
        val barX = 1280 - 8; val barY = PAD_TOP + 4
        val barH = 720 - PAD_TOP - PAD_BOT - 8
        if (x in (barX - 4f)..(barX + 10f) && y in barY.toFloat()..(barY + barH).toFloat()) {
            draggingScrollbar = true
            dragStartY        = y
            dragStartOffset   = scrollOffset
        }
    }

    override fun mouseReleased(x: Float, y: Float, button: Int, mods: Int) {
        draggingScrollbar = false
    }

    override fun mouseDragged(x: Float, y: Float, button: Int) {
        if (!draggingScrollbar) return
        val barH = 720 - PAD_TOP - PAD_BOT - 8
        val visL = (720 - PAD_TOP - PAD_BOT) / LINE_H
        val maxS = maxOf(1, lines.size - visL)
        val dy   = y - dragStartY
        val delta= (dy / barH * maxS).toInt()
        scrollOffset = (dragStartOffset + delta).coerceIn(0, maxS)
    }
}
