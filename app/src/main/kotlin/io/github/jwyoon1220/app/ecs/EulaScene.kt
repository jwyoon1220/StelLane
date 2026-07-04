package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.AppSettings
import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.engine.GameState
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand
import kotlin.math.max
import kotlin.system.exitProcess

/**
 * EULA 화면. returnTo != null이면 읽기 전용 모드(설정에서 다시 보기),
 * null이면 최초 동의 모드(끝까지 스크롤해야 [동의함] 활성화).
 */
class EulaScene(
    private val ctx: GameContext,
    private val returnTo: GameState? = null
) : Scene() {

    private val isReadOnly = returnTo != null

    private val linesKo: List<String> = EulaScene::class.java.getResourceAsStream("/eula.txt")?.use { stream ->
        stream.bufferedReader(Charsets.UTF_8).readLines()
    } ?: listOf("EULA 파일을 찾을 수 없습니다. (EULA file not found)")

    private val linesEn: List<String> = EulaScene::class.java.getResourceAsStream("/eula_en.txt")?.use { stream ->
        stream.bufferedReader(Charsets.UTF_8).readLines()
    } ?: listOf("EULA file not found.")

    private var isEnglish = false
    private val lines get() = if (isEnglish) linesEn else linesKo

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
    private val langFont   = FontLoader.semiBold(12f)

    private var draggingScrollbar = false
    private var dragStartY        = 0f
    private var dragStartOffset   = 0

    private var mouseX = 0f
    private var mouseY = 0f

    override fun enter() {
        super.enter()
        scrollOffset  = 0
        reachedBottom = isReadOnly
        ctx.inputManager.clearEvents()
        register(EulaRenderSystem())
    }

    private inner class EulaRenderSystem : RenderProducer {
        override fun update(world: World, input: InputSnapshot, deltaTime: Double) {
            mouseX = input.cursorX
            mouseY = input.cursorY
        }
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
        g.drawString(if (isEnglish) "End User License Agreement (EULA)" else "최종 사용자 라이선스 계약 (EULA)", PAD_LEFT.toFloat(), 38f)

        // 언어 토글 버튼 (KO / EN)
        val langBtnW = 72f; val langBtnH = 24f
        val langBtnX = (w - PAD_LEFT).toFloat() - langBtnW
        val langBtnY = 18f
        val langHover = mouseX in langBtnX..(langBtnX + langBtnW) && mouseY in langBtnY..(langBtnY + langBtnH)
        g.renderColor = if (langHover) RenderColor.of(55, 30, 100, 220) else RenderColor.of(35, 20, 70, 200)
        g.fillRoundRect(langBtnX, langBtnY, langBtnW, langBtnH, 6f)
        g.renderColor = if (langHover) RenderColor.of(150, 90, 230, 220) else RenderColor.of(100, 60, 180, 160)
        g.drawRoundRect(langBtnX, langBtnY, langBtnW, langBtnH, 6f)
        g.font = langFont
        g.renderColor = if (!isEnglish) RenderColor.of(210, 175, 255) else RenderColor.of(90, 75, 130)
        g.drawStringCentered("KO", langBtnX + langBtnW / 4f, langBtnY + langBtnH - 6f)
        g.renderColor = RenderColor.of(80, 55, 130, 160)
        g.drawLine(
            (langBtnX + langBtnW / 2f).toInt(), (langBtnY + 4f).toInt(),
            (langBtnX + langBtnW / 2f).toInt(), (langBtnY + langBtnH - 4f).toInt()
        )
        g.renderColor = if (isEnglish) RenderColor.of(210, 175, 255) else RenderColor.of(90, 75, 130)
        g.drawStringCentered("EN", langBtnX + langBtnW * 3f / 4f, langBtnY + langBtnH - 6f)

        // 본문
        val visibleLines = (h - PAD_TOP - PAD_BOT) / LINE_H
        val maxScroll    = max(0, lines.size - visibleLines)
        scrollOffset     = scrollOffset.coerceIn(0, maxScroll)

        if (!isReadOnly && scrollOffset >= maxScroll) reachedBottom = true

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

        if (isReadOnly) {
            // 읽기 전용: 닫기 버튼
            g.font = hintFont
            g.renderColor = RenderColor.of(80, 65, 110)
            g.drawStringCentered(
                if (isEnglish) "Esc or click [Close] to go back" else "Esc 또는 [닫기]를 클릭해서 돌아가기",
                w / 2f, h - PAD_BOT + 24f
            )
            val closeW = 130f; val closeH = 36f
            val closeX = w / 2f - closeW / 2f; val closeY = h - 46f
            val closeHover = mouseX in closeX..(closeX + closeW) && mouseY in closeY..(closeY + closeH)
            g.renderColor = if (closeHover) RenderColor.of(80, 45, 160, 240) else RenderColor.of(60, 30, 130, 220)
            g.fillRoundRect(closeX, closeY, closeW, closeH, 8f)
            g.renderColor = if (closeHover) RenderColor.of(180, 120, 255) else RenderColor.of(140, 80, 255)
            g.drawRoundRect(closeX, closeY, closeW, closeH, 8f)
            g.font = btnFont
            g.renderColor = RenderColor.of(230, 210, 255)
            g.drawStringCentered(if (isEnglish) "Close" else "닫기", closeX + closeW / 2f, closeY + closeH - 10f)
        } else {
            // 최초 동의 모드
            if (!reachedBottom) {
                g.font = hintFont
                g.renderColor = RenderColor.of(140, 110, 180)
                g.drawStringCentered(
                    if (isEnglish) "Scroll to the bottom to enable [Agree] button"
                    else "내용을 끝까지 스크롤하면 [동의함] 버튼이 활성화됩니다",
                    w / 2f, h - PAD_BOT + 24f
                )
            }

            // [동의함] 버튼
            val agreeW = 130f; val agreeH = 36f
            val agreeX = w / 2f + 6f; val agreeY = h - 46f
            val agreeHover = reachedBottom && mouseX in agreeX..(agreeX + agreeW) && mouseY in agreeY..(agreeY + agreeH)
            if (reachedBottom) {
                g.renderColor = if (agreeHover) RenderColor.of(80, 45, 160, 240) else RenderColor.of(60, 30, 130, 220)
                g.fillRoundRect(agreeX, agreeY, agreeW, agreeH, 8f)
                g.renderColor = if (agreeHover) RenderColor.of(180, 120, 255) else RenderColor.of(140, 80, 255)
                g.drawRoundRect(agreeX, agreeY, agreeW, agreeH, 8f)
                g.font = btnFont
                g.renderColor = RenderColor.of(230, 210, 255)
                g.drawStringCentered(if (isEnglish) "Agree" else "동의함", agreeX + agreeW / 2f, agreeY + agreeH - 10f)
            } else {
                g.renderColor = RenderColor.of(30, 25, 55, 120)
                g.fillRoundRect(agreeX, agreeY, agreeW, agreeH, 8f)
                g.renderColor = RenderColor.of(60, 50, 90, 100)
                g.drawRoundRect(agreeX, agreeY, agreeW, agreeH, 8f)
                g.font = btnFont
                g.renderColor = RenderColor.of(90, 80, 120)
                g.drawStringCentered(if (isEnglish) "Agree" else "동의함", agreeX + agreeW / 2f, agreeY + agreeH - 10f)
            }

            // [게임 종료] 버튼
            val quitW = 130f; val quitH = 36f
            val quitX = w / 2f - quitW - 6f; val quitY = h - 46f
            val quitHover = mouseX in quitX..(quitX + quitW) && mouseY in quitY..(quitY + quitH)
            g.renderColor = if (quitHover) RenderColor.of(65, 30, 55, 210) else RenderColor.of(40, 20, 40, 180)
            g.fillRoundRect(quitX, quitY, quitW, quitH, 8f)
            g.renderColor = if (quitHover) RenderColor.of(180, 90, 120) else RenderColor.of(120, 60, 90)
            g.drawRoundRect(quitX, quitY, quitW, quitH, 8f)
            g.font = btnFont
            g.renderColor = if (quitHover) RenderColor.of(220, 170, 190) else RenderColor.of(180, 140, 160)
            g.drawStringCentered(if (isEnglish) "Quit" else "게임 종료", quitX + quitW / 2f, quitY + quitH - 10f)
        }
    }

    override fun keyPressed(key: Int, mods: Int) {
        when (key) {
            Keys.UP        -> scrollOffset = max(0, scrollOffset - 1)
            Keys.DOWN      -> scrollOffset++
            Keys.PAGE_UP   -> scrollOffset = max(0, scrollOffset - 20)
            Keys.PAGE_DOWN -> scrollOffset += 20
            Keys.HOME      -> scrollOffset = 0
            Keys.END       -> { scrollOffset = lines.size; if (!isReadOnly) reachedBottom = true }
            Keys.ESCAPE    -> if (isReadOnly) ctx.sceneRouter.navigate(returnTo!!)
        }
    }

    override fun mouseScrolled(dy: Double) {
        scrollOffset = if (dy < 0) scrollOffset + 3 else max(0, scrollOffset - 3)
    }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        val h = 720
        val w = 1280

        // 언어 토글 버튼 클릭
        val langBtnW = 72f; val langBtnH = 24f
        val langBtnX = (w - PAD_LEFT).toFloat() - langBtnW
        val langBtnY = 18f
        if (x in langBtnX..(langBtnX + langBtnW) && y in langBtnY..(langBtnY + langBtnH)) {
            isEnglish = !isEnglish
            scrollOffset = 0
            if (!isReadOnly) reachedBottom = false
            return
        }

        if (isReadOnly) {
            val closeW = 130f; val closeH = 36f
            val closeX = w / 2f - closeW / 2f; val closeY = h - 46f
            if (x in closeX..(closeX + closeW) && y in closeY..(closeY + closeH)) {
                ctx.sceneRouter.navigate(returnTo!!)
            }
        } else {
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
}
