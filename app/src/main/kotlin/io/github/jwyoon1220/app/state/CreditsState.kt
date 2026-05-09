package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.core.GameState
import java.awt.Color
import java.awt.Graphics2D
import java.awt.event.KeyEvent

class CreditsState(private val ctx: GameContext) : GameState {

    private val sections = listOf(
        Section("StelLane", listOf(
            Entry("개발", "jwyoon1220"),
            Entry("버전", "1.0-SNAPSHOT")
        )),
        Section("폰트", listOf(
            Entry("MaruBuri", "NAVER Corporation"),
            Entry("라이선스", "SIL Open Font License 1.1")
        )),
        Section("소프트웨어", listOf(
            Entry("VideoLAN VLC Media Player", "VideoLAN — GPL-2.0+"),
        )),
        Section("오픈소스 라이브러리", listOf(
            Entry("Kotlin",                "JetBrains s.r.o. — Apache-2.0"),
            Entry("kotlinx-coroutines",    "JetBrains s.r.o. — Apache-2.0"),
            Entry("vlcj",                  "Caprica Software — GPL-3.0"),
            Entry("Jackson",               "FasterXML, LLC — Apache-2.0"),
            Entry("Logback",               "QOS.ch — EPL-2.0 / LGPL-2.1"),
            Entry("SLF4J",                 "QOS.ch — MIT"),
            Entry("FastUtil",              "Sebastiano Vigna — Apache-2.0"),
            Entry("Apache Commons CLI",    "Apache Software Foundation — Apache-2.0"),
            Entry("JNA",                   "JNA contributors — Apache-2.0 / LGPL-2.1")
        )),
        Section("Special Thanks to", listOf(
            Entry("", "이 게임을 플레이해주신 모든 분들"),
        ))
    )

    private data class Section(val title: String, val entries: List<Entry>)
    private data class Entry(val key: String, val value: String)

    private val titleFont   = FontLoader.bold(48f)
    private val sectionFont = FontLoader.semiBold(18f)
    private val bodyFont    = FontLoader.regular(15f)
    private val hintFont    = FontLoader.light(12f)

    override fun enter() { ctx.inputManager.clearEvents() }
    override fun exit()  {}
    override fun update(deltaTime: Double) {}

    override fun render(g: Graphics2D) {
        val w = g.clipBounds?.width  ?: 1280
        val h = g.clipBounds?.height ?: 720

        // 배경 그라디언트
        g.color = Color(8, 8, 20)
        g.fillRect(0, 0, w, h)
        // 하단 그라디언트 효과
        for (i in 0..80) {
            g.color = Color(30, 10, 50, (i * 2).coerceAtMost(255))
            g.drawLine(0, h - i, w, h - i)
        }

        // 타이틀
        g.font  = titleFont
        val fm  = g.getFontMetrics(titleFont)
        val tx  = (w - fm.stringWidth("StelLane")) / 2
        g.color = Color(180, 100, 255, 60)
        g.drawString("StelLane", tx + 2, 82)
        g.color = Color(200, 160, 255)
        g.drawString("StelLane", tx, 80)

        g.font  = hintFont
        g.color = Color(120, 100, 160)
        val sub = "Credits"
        val sfm = g.getFontMetrics(hintFont)
        g.drawString(sub, (w - sfm.stringWidth(sub)) / 2, 104)

        // 구분선
        g.color = Color(60, 40, 90)
        g.drawLine(w / 4, 116, w * 3 / 4, 116)

        // 섹션들
        var cy = 144
        val colKey = w / 2 - 260
        val colVal = w / 2 + 20

        for (section in sections) {
            g.font  = sectionFont
            g.color = Color(160, 100, 230)
            g.drawString(section.title, colKey, cy)
            cy += 8
            g.color = Color(50, 30, 80)
            g.drawLine(colKey, cy, colVal + 300, cy)
            cy += 18

            for (entry in section.entries) {
                g.font  = bodyFont
                if (entry.key.isNotEmpty()) {
                    g.color = Color(180, 180, 200)
                    g.drawString(entry.key, colKey + 10, cy)
                }
                g.color = Color(220, 220, 230)
                g.drawString(entry.value, colVal, cy)
                cy += 22
            }
            cy += 14
        }

        // 하단 힌트
        g.font  = hintFont
        g.color = Color(80, 60, 110)
        val hint = "Esc 또는 Enter — 뒤로"
        val hfm  = g.getFontMetrics(hintFont)
        g.drawString(hint, (w - hfm.stringWidth(hint)) / 2, h - 20)
    }

    override fun keyPressed(e: KeyEvent) {
        when (e.keyCode) {
            KeyEvent.VK_ESCAPE, KeyEvent.VK_ENTER ->
                ctx.stateManager.changeState(MainMenuState(ctx))
        }
    }
}
