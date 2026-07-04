package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.AppSettings
import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand
import kotlin.math.max
import kotlin.system.exitProcess

/** 최초 실행 시 표시되는 EULA 동의 화면. 끝까지 스크롤해야 [동의함] 버튼이 활성화됩니다. */
class EulaScene(private val ctx: GameContext) : Scene() {

    private val lines: List<String> = EULA_LINES

    private var scrollOffset = 0
    private var reachedBottom = false

    private val LINE_H   = 18
    private val PAD_TOP  = 62
    private val PAD_BOT  = 72
    private val PAD_LEFT = 32

    private val titleFont  = FontLoader.semiBold(20f)
    private val bodyFont   = FontLoader.regular(13f)
    private val hintFont   = FontLoader.light(11f)
    private val btnFont    = FontLoader.semiBold(15f)

    private var draggingScrollbar = false
    private var dragStartY        = 0f
    private var dragStartOffset   = 0

    override fun enter() {
        super.enter()
        scrollOffset  = 0
        reachedBottom = false
        ctx.inputManager.clearEvents()
        register(EulaRenderSystem())
    }

    private inner class EulaRenderSystem : RenderProducer {
        override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit
        override fun produce(world: World, out: MutableList<RenderCommand>) {
            out.add(RenderCommand.LegacyDrawContext { renderContents(this) })
        }
    }

    private fun renderContents(g: io.github.jwyoon1220.engine.DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height

        g.renderColor = RenderColor.of(8, 6, 18)
        g.fillRect(0, 0, w, h)

        // 헤더
        g.fillLinearGradient(
            0f, 0f, w.toFloat(), PAD_TOP.toFloat(),
            0f, 0f, 0f, PAD_TOP.toFloat(),
            RenderColor.of(24, 14, 52, 240), RenderColor.of(10, 6, 24, 240)
        )
        g.renderColor = RenderColor.of(50, 28, 100, 140)
        g.drawLine(0, PAD_TOP, w, PAD_TOP)
        g.font = titleFont
        g.renderColor = RenderColor.of(210, 175, 255)
        g.drawString("최종 사용자 라이선스 계약 (EULA)", PAD_LEFT.toFloat(), 38f)
        g.font = hintFont
        g.renderColor = RenderColor.of(80, 65, 110)
        g.drawStringRight("↑↓ / 스크롤: 내용 보기", (w - PAD_LEFT).toFloat(), 38f)

        // 본문
        val visibleLines = (h - PAD_TOP - PAD_BOT) / LINE_H
        val maxScroll    = max(0, lines.size - visibleLines)
        scrollOffset     = scrollOffset.coerceIn(0, maxScroll)

        if (scrollOffset >= maxScroll) reachedBottom = true

        g.font = bodyFont
        g.scoped {
            setClip(0, PAD_TOP, w - 12, h - PAD_TOP - PAD_BOT)
            for (i in 0 until visibleLines) {
                val lineIdx = scrollOffset + i
                if (lineIdx >= lines.size) break
                val line  = lines[lineIdx]
                val lineY = (PAD_TOP + (i + 1) * LINE_H).toFloat()
                g.renderColor = when {
                    line.startsWith("【") || line.startsWith("제") -> RenderColor.of(200, 170, 255)
                    line.startsWith("  ·") || line.startsWith("  -") -> RenderColor.of(165, 158, 200)
                    line.isBlank() -> RenderColor.of(0, 0, 0, 0)
                    else -> RenderColor.of(185, 178, 215)
                }
                if (!line.isBlank()) g.drawString(line, PAD_LEFT.toFloat(), lineY)
                else {
                    g.renderColor = RenderColor.of(30, 22, 55, 80)
                    g.drawLine(PAD_LEFT, lineY.toInt(), w - 24, lineY.toInt())
                }
            }
        }

        // 스크롤바
        if (lines.size > visibleLines) {
            val barX   = w - 8
            val barY   = PAD_TOP + 4
            val barH   = h - PAD_TOP - PAD_BOT - 8
            val thumbH = max(24, barH * visibleLines / lines.size)
            val thumbY = barY + (barH - thumbH) * scrollOffset / max(1, maxScroll)
            g.renderColor = RenderColor.of(22, 16, 40)
            g.fillRoundRect(barX.toFloat(), barY.toFloat(), 6f, barH.toFloat(), 3f)
            g.renderColor = RenderColor.of(90, 55, 150, 180)
            g.fillRoundRect(barX.toFloat(), thumbY.toFloat(), 6f, thumbH.toFloat(), 3f)
        }

        // 진행률 바
        val progress = if (maxScroll > 0) scrollOffset.toFloat() / maxScroll else 1f
        g.renderColor = RenderColor.of(35, 22, 70)
        g.fillRect(0, h - PAD_BOT, w, 4)
        g.renderColor = RenderColor.of(120, 70, 220, 180)
        g.fillRect(0, h - PAD_BOT, (w * progress).toInt().coerceAtLeast(4), 4)

        // 하단 버튼 바
        g.renderColor = RenderColor.of(12, 8, 28, 230)
        g.fillRect(0, h - PAD_BOT + 4, w, PAD_BOT - 4)
        g.renderColor = RenderColor.of(40, 28, 70, 120)
        g.drawLine(0, h - PAD_BOT + 4, w, h - PAD_BOT + 4)

        if (!reachedBottom) {
            g.font = hintFont
            g.renderColor = RenderColor.of(140, 110, 180)
            g.drawStringCentered("내용을 끝까지 스크롤하면 [동의함] 버튼이 활성화됩니다", w / 2f, h - PAD_BOT + 24f)
        }

        // [동의함] 버튼
        val agreeW = 130f; val agreeH = 36f
        val agreeX = w / 2f + 6f; val agreeY = h - 46f
        if (reachedBottom) {
            g.renderColor = RenderColor.of(60, 30, 130, 220)
            g.fillRoundRect(agreeX, agreeY, agreeW, agreeH, 8f)
            g.renderColor = RenderColor.of(140, 80, 255)
            g.drawRoundRect(agreeX, agreeY, agreeW, agreeH, 8f)
            g.font = btnFont
            g.renderColor = RenderColor.of(230, 210, 255)
            g.drawStringCentered("동의함", agreeX + agreeW / 2f, agreeY + agreeH - 10f)
        } else {
            g.renderColor = RenderColor.of(30, 25, 55, 120)
            g.fillRoundRect(agreeX, agreeY, agreeW, agreeH, 8f)
            g.renderColor = RenderColor.of(60, 50, 90, 100)
            g.drawRoundRect(agreeX, agreeY, agreeW, agreeH, 8f)
            g.font = btnFont
            g.renderColor = RenderColor.of(90, 80, 120)
            g.drawStringCentered("동의함", agreeX + agreeW / 2f, agreeY + agreeH - 10f)
        }

        // [게임 종료] 버튼
        val quitW = 130f; val quitH = 36f
        val quitX = w / 2f - quitW - 6f; val quitY = h - 46f
        g.renderColor = RenderColor.of(40, 20, 40, 180)
        g.fillRoundRect(quitX, quitY, quitW, quitH, 8f)
        g.renderColor = RenderColor.of(120, 60, 90)
        g.drawRoundRect(quitX, quitY, quitW, quitH, 8f)
        g.font = btnFont
        g.renderColor = RenderColor.of(180, 140, 160)
        g.drawStringCentered("게임 종료", quitX + quitW / 2f, quitY + quitH - 10f)
    }

    override fun keyPressed(key: Int, mods: Int) {
        when (key) {
            Keys.UP        -> scrollOffset = max(0, scrollOffset - 1)
            Keys.DOWN      -> scrollOffset++
            Keys.PAGE_UP   -> scrollOffset = max(0, scrollOffset - 20)
            Keys.PAGE_DOWN -> scrollOffset += 20
            Keys.HOME      -> scrollOffset = 0
            Keys.END       -> { scrollOffset = lines.size; reachedBottom = true }
        }
    }

    override fun mouseScrolled(dy: Double) {
        scrollOffset = if (dy < 0) scrollOffset + 3 else max(0, scrollOffset - 3)
    }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        val h = 720
        val w = 1280
        val agreeW = 130f; val agreeH = 36f
        val agreeX = w / 2f + 6f; val agreeY = h - 46f
        val quitW  = 130f; val quitH  = 36f
        val quitX  = w / 2f - quitW - 6f; val quitY = h - 46f

        if (reachedBottom && x in agreeX..(agreeX + agreeW) && y in agreeY..(agreeY + agreeH)) {
            AppSettings.eulaAccepted = true
            ctx.sceneRouter.navigate(MainMenuScene(ctx))
        } else if (x in quitX..(quitX + quitW) && y in quitY..(quitY + quitH)) {
            exitProcess(0)
        }
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
        val maxS = max(1, lines.size - visL)
        val dy   = y - dragStartY
        val delta = (dy / barH * maxS).toInt()
        scrollOffset = (dragStartOffset + delta).coerceIn(0, maxS)
    }

    companion object {
        private val EULA_LINES = """
StelLane 최종 사용자 라이선스 계약 (EULA)
최종 업데이트: 2026년 7월

이 계약을 끝까지 읽고 동의해 주세요.

【제1조 정의】
  · "소프트웨어"란 StelLane 리듬 게임 클라이언트 및 에디터를 말합니다.
  · "사용자 콘텐츠"란 사용자가 직접 로드하거나 제작·공유하는 음원, 영상,
    이미지, 채보(맵) 파일 등 모든 미디어 및 데이터를 말합니다.
  · "개발팀"이란 StelLane 소스코드의 기여자 및 배포자를 말합니다.

【제2조 허용 범위】
  · 사용자는 이 소프트웨어를 개인적·비영리 목적으로 자유롭게 사용할 수 있습니다.
  · 소스코드는 Apache License 2.0(루트) 및 GPL 3.0(engine 모듈)에 따라 공개됩니다.

【제3조 저작권 및 사용자 책임】
  · 이 소프트웨어는 음원·영상·이미지 등 미디어 파일을 직접 제공하지 않습니다.
  · 사용자가 소프트웨어에 로드하거나 타인과 공유하는 모든 사용자 콘텐츠에 대한
    저작권법 준수 여부는 사용자 본인의 책임입니다.
  · 저작권자의 허락 없이 타인의 음원·영상·이미지를 포함한 맵 파일을
    온라인상에 무단으로 공유하거나 배포하는 행위는 저작권법에 위반될 수 있으며,
    이에 따른 모든 법적 책임은 해당 사용자 본인에게 있습니다.

【제4조 멀티플레이 및 콘텐츠 공유】
  · 멀티플레이 기능을 통해 세션을 호스팅하거나 콘텐츠를 공유할 경우,
    공유되는 사용자 콘텐츠의 저작권 문제에 대한 책임은 호스트 사용자에게 있습니다.
  · 개발팀은 멀티플레이 과정에서 발생하는 저작권 분쟁, 데이터 유출,
    제3자 피해 등에 대해 어떠한 법적 책임도 지지 않습니다.

【제5조 팬게임 고지】
  · StelLane은 스텔라이브(Stellive) 팬이 만든 비공식 팬게임으로,
    Stellive 및 소속 크리에이터와 공식적인 관계가 없습니다.
  · 원저작자의 저작물을 존중하며, 영리 목적의 사용을 금합니다.

【제6조 면책 조항】
  · 이 소프트웨어는 "있는 그대로(AS IS)" 제공됩니다.
  · 개발팀은 소프트웨어 사용으로 인한 직접적·간접적 손해, 데이터 손실,
    제3자 저작권 분쟁 등에 대해 어떠한 보증도 하지 않으며 책임을 지지 않습니다.
  · 사용자는 이 계약에 동의함으로써 위 내용을 충분히 이해하고 동의한 것으로 간주됩니다.

【제7조 계약 변경】
  · 개발팀은 소프트웨어 업데이트와 함께 이 계약을 수정할 수 있습니다.
  · 변경 시 다음 실행 시 재동의가 요청될 수 있습니다.

이 내용을 모두 읽고 이해하셨다면 아래 [동의함] 버튼을 눌러 계속하세요.
동의하지 않으시면 [게임 종료] 버튼을 눌러 종료할 수 있습니다.
        """.trimIndent().lines()
    }
}
