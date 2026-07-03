package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand
import kotlin.math.sin

/**
 * 크레딧 화면 (ECS Scene).
 *
 * 순수 UI 화면이라 도메인 엔터티 없이 Scene 백본 위에서 렌더링만 수행합니다.
 */
class CreditsScene(private val ctx: GameContext) : Scene() {

    private val sections = listOf(
        Section("StelLane", listOf(
            Entry("개발", "jwyoon1220"),
            Entry("버전", io.github.jwyoon1220.app.Const.VERSION)
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

    private val titleFont   = FontLoader.bold(56f)
    private val subtitleFont= FontLoader.extraLight(14f)
    private val sectionFont = FontLoader.semiBold(16f)
    private val bodyFont    = FontLoader.regular(14f)
    private val hintFont    = FontLoader.light(12f)

    private var time = 0.0

    override fun enter() {
        super.enter()
        ctx.inputManager.clearEvents()
        time = 0.0
        register(CreditsRenderSystem())
    }

    override fun onUpdate(deltaTime: Double) { time += deltaTime }

    private inner class CreditsRenderSystem : RenderProducer {
        override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit
        override fun produce(world: World, out: MutableList<RenderCommand>) {
            out.add(RenderCommand.LegacyDrawContext { renderContents(this) })
        }
    }

    private fun renderContents(g: io.github.jwyoon1220.engine.DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height
        val t = time.toFloat()

        // ── 배경 ─────────────────────────────────────────────────────────────
        g.renderColor = RenderColor.of(6, 4, 16)
        g.fillRect(0, 0, w, h)

        // 상단 보라 그라디언트
        g.fillLinearGradient(
            0f, 0f, w.toFloat(), 280f,
            0f, 0f, 0f, 280f,
            RenderColor.of(30, 10, 70, 180), RenderColor.of(0, 0, 0, 0)
        )
        // 하단 페이드
        g.fillLinearGradient(
            0f, (h - 80).toFloat(), w.toFloat(), 80f,
            0f, (h - 80).toFloat(), 0f, h.toFloat(),
            RenderColor.of(0, 0, 0, 0), RenderColor.of(6, 4, 16, 255)
        )

        // ── 장식 원 ───────────────────────────────────────────────────────────
        val circleR = 200f + sin(t * 0.4f).toFloat() * 8f
        g.fillRadialGradient(
            w / 2f - circleR, 120f - circleR, circleR * 2, circleR * 2,
            w / 2f, 120f, 0f, circleR,
            RenderColor.of(80, 35, 160, 28), RenderColor.of(0, 0, 0, 0)
        )

        // ── 타이틀 ────────────────────────────────────────────────────────────
        g.font  = titleFont
        g.renderColor = RenderColor.of(140, 70, 255, 55)
        g.setFontBlur(10f)
        g.drawStringCentered("StelLane", w / 2f, 96f)
        g.setFontBlur(0f)
        g.renderColor = RenderColor.of(220, 195, 255)
        g.drawStringCentered("StelLane", w / 2f, 96f)

        g.font  = subtitleFont
        g.renderColor = RenderColor.of(110, 85, 160, 200)
        g.drawStringCentered("CREDITS", w / 2f, 116f)

        // 구분선
        val lineAlpha = (sin(t * 1.2f) * 20 + 80).toInt()
        g.renderColor = RenderColor.of(80, 50, 140, lineAlpha)
        g.stroke = java.awt.BasicStroke(1f)
        g.drawLine(w / 2 - 180, 130, w / 2 + 180, 130)

        // ── 섹션들 ────────────────────────────────────────────────────────────
        var cy    = 158f
        val colKey= (w / 2 - 280).toFloat()
        val colVal= (w / 2 + 20).toFloat()

        for (section in sections) {
            // 섹션 헤더 배경
            g.renderColor = RenderColor.of(35, 18, 75, 120)
            g.fillRoundRect(colKey - 12f, cy - 16f, 580f, 26f, 6f)

            // 섹션 제목 글로우
            g.font  = sectionFont
            g.renderColor = RenderColor.of(120, 60, 220, 60)
            g.setFontBlur(2f)
            g.drawString(section.title, colKey, cy)
            g.setFontBlur(0f)
            g.renderColor = RenderColor.of(170, 115, 255)
            g.drawString(section.title, colKey, cy)

            cy += 6f
            g.renderColor = RenderColor.of(55, 30, 100, 100)
            g.drawLine((colKey - 12).toInt(), cy.toInt(), (colKey + 568).toInt(), cy.toInt())
            cy += 18f

            for (entry in section.entries) {
                g.font = bodyFont
                if (entry.key.isNotEmpty()) {
                    g.renderColor = RenderColor.of(150, 135, 185)
                    g.drawString(entry.key, colKey + 12f, cy)
                }
                g.renderColor = RenderColor.of(210, 205, 225)
                g.drawString(entry.value, colVal, cy)
                cy += 22f
            }
            cy += 18f
        }

        // ── 하단 힌트 ─────────────────────────────────────────────────────────
        g.font  = hintFont
        g.renderColor = RenderColor.of(65, 52, 95)
        g.drawStringCentered("Esc 또는 Enter — 메인으로 돌아가기   클릭으로도 돌아갑니다", w / 2f, (h - 20).toFloat())
    }

    override fun keyPressed(key: Int, mods: Int) {
        when (key) {
            Keys.ESCAPE, Keys.ENTER -> ctx.sceneRouter.navigate(MainMenuScene(ctx))
        }
    }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        ctx.sceneRouter.navigate(MainMenuScene(ctx))
    }
}
