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

    private val lineHeight   = 16
    private val paddingTop   = 70
    private val paddingBot   = 50

    private val titleFont    = FontLoader.semiBold(22f)
    private val bodyFont     = FontLoader.regular(13f)
    private val hintFont     = FontLoader.light(12f)

    override fun enter() {
        scrollOffset = 0
        ctx.inputManager.clearEvents()
    }

    override fun exit() {}

    override fun update(deltaTime: Double) {}

    override fun render(g: DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height

        // 배경
        g.color = Color(10, 10, 22)
        g.fillRect(0, 0, w, h)

        // 헤더
        g.color = Color(30, 30, 55)
        g.fillRect(0, 0, w, 52)
        g.color = Color(60, 60, 100)
        g.drawLine(0, 52, w, 52)

        g.font  = titleFont
        g.color = Color(200, 160, 255)
        g.drawString("오픈소스 라이선스", 24, 36)

        // 힌트
        g.font  = hintFont
        g.color = Color(100, 100, 130)
        g.drawString("↑↓ / PgUp·PgDn: 스크롤   Esc: 뒤로", w - 310, 36)

        // 본문
        val visibleLines = (h - paddingTop - paddingBot) / lineHeight
        val maxScroll    = maxOf(0, lines.size - visibleLines)
        scrollOffset     = scrollOffset.coerceIn(0, maxScroll)

        g.font = bodyFont
        val fm = g.getFontMetrics(bodyFont)

        for (i in 0 until visibleLines) {
            val lineIdx = scrollOffset + i
            if (lineIdx >= lines.size) break
            val line = lines[lineIdx]

            // 구분선 / 섹션 헤더 색상 처리
            g.color = when {
                line.startsWith("===") || line.startsWith("---") -> Color(60, 60, 100)
                line.startsWith("Applies to") || line.startsWith("Component") -> Color(160, 220, 255)
                line.startsWith("  -") -> Color(180, 180, 200)
                line.isBlank() -> Color(0, 0, 0, 0)
                else -> Color(200, 200, 210)
            }
            if (!line.isBlank()) {
                g.drawString(line.take((w - 48) / maxOf(1, fm.stringWidth("m"))), 24, paddingTop + i * lineHeight)
            }
        }

        // 스크롤 바
        if (lines.size > visibleLines) {
            val barH    = h - paddingTop - paddingBot
            val thumbH  = maxOf(20, barH * visibleLines / lines.size)
            val thumbY  = paddingTop + (barH - thumbH) * scrollOffset / maxOf(1, maxScroll)
            g.color = Color(40, 40, 70)
            g.fillRect(w - 10, paddingTop, 6, barH)
            g.color = Color(120, 120, 180)
            g.fillRect(w - 10, thumbY, 6, thumbH)
        }

        // 하단 힌트
        g.font  = hintFont
        g.color = Color(70, 70, 100)
        g.drawString("${scrollOffset + 1} / ${lines.size} lines", 24, h - 16)
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
}
