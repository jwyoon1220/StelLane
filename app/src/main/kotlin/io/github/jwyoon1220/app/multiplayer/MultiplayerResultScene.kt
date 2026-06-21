package io.github.jwyoon1220.app.multiplayer

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.ecs.MainMenuScene
import io.github.jwyoon1220.app.multiplayer.proto.RankEntry
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.GameState
import io.github.jwyoon1220.engine.render.RenderColor
import kotlin.math.sin

/**
 * 멀티플레이어 최종 결과 화면.
 * GameOverMsg로 받은 RankEntry 목록을 표시하고 Enter/Esc로 메인 메뉴로 돌아갑니다.
 */
class MultiplayerResultScene(
    private val ctx: GameContext,
    private val entries: List<RankEntry>,
    private val manager: MultiplayerManager
) : GameState {

    private val titleFont  = FontLoader.bold(52f)
    private val rankFont   = FontLoader.bold(22f)
    private val nameFont   = FontLoader.semiBold(20f)
    private val statFont   = FontLoader.regular(17f)
    private val hintFont   = FontLoader.light(12f)
    private val crownFont  = FontLoader.bold(26f)

    private var time = 0.0

    override fun enter() { time = 0.0; manager.onGameOver = null }
    override fun exit()  { manager.stop(); ctx.multiplayerManager = null }
    override fun update(deltaTime: Double) { time += deltaTime }

    override fun render(g: DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height
        val t = time.toFloat()

        // 배경
        g.renderColor = RenderColor.of(0, 0, 0, 210)
        g.fillRect(0, 0, w, h)

        val pw = 640; val ph = 80 + entries.size.coerceAtLeast(1) * 72 + 80
        val px = (w - pw) / 2; val py = ((h - ph) / 2).coerceAtLeast(20)

        // 패널
        g.fillLinearGradient(
            px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(),
            px.toFloat(), py.toFloat(), px.toFloat(), (py + ph).toFloat(),
            RenderColor.of(20, 12, 45, 252), RenderColor.of(10, 6, 28, 252)
        )
        val gA = (sin(t * 1.2f) * 15 + 75).toInt()
        g.renderColor = RenderColor.of(120, 70, 220, gA)
        g.drawRoundRect(px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(), 16f)

        // 타이틀
        g.font = titleFont
        g.renderColor = RenderColor.of(200, 160, 255, (sin(t * 0.6f) * 20 + 235).toInt())
        g.drawStringCentered("결 과", w / 2f, py + 58f)

        // 구분선
        g.renderColor = RenderColor.of(80, 50, 150, 120)
        g.drawLine(px + 24, py + 68, px + pw - 24, py + 68)

        // 순위 목록
        val rowH = 72
        val listY = py + 80

        entries.forEachIndexed { i, entry ->
            val ry = listY + i * rowH
            val isMe = entry.name == manager.localPlayerName
            val isFirst = i == 0

            // 내 결과 강조 배경
            if (isMe) {
                val hiA = (sin(t * 2.5f) * 20 + 55).toInt()
                g.renderColor = RenderColor.of(100, 55, 200, hiA)
                g.fillRoundRect((px + 10).toFloat(), ry.toFloat(), (pw - 20).toFloat(), (rowH - 6).toFloat(), 10f)
            }

            // 순위 번호
            g.font = rankFont
            val rankColor = when (i) {
                0 -> RenderColor.of(255, 210, 50)
                1 -> RenderColor.of(190, 200, 210)
                2 -> RenderColor.of(200, 130, 80)
                else -> RenderColor.of(120, 110, 150)
            }
            g.renderColor = rankColor
            val rankStr = if (isFirst) "👑" else "${i + 1}."
            g.drawString(if (isFirst) "1." else "${i + 1}.", (px + 24).toFloat(), ry + rowH - 22f)

            // 왕관 (1위)
            if (isFirst) {
                g.font = crownFont
                g.renderColor = RenderColor.of(255, 220, 60, (sin(t * 3f) * 30 + 225).toInt())
                g.drawString("★", (px + 50).toFloat(), ry + rowH - 22f)
            }

            // 이름
            g.font = nameFont
            g.renderColor = if (isMe) RenderColor.of(255, 240, 120) else RenderColor.of(210, 200, 235)
            val displayName = entry.name.take(14)
            g.drawString(displayName, (px + 80).toFloat(), ry + rowH - 22f)

            // 점수
            g.font = statFont
            g.renderColor = RenderColor.of(160, 220, 160)
            g.drawStringRight("%,d".format(entry.score), (px + pw - 130).toFloat(), ry + rowH - 22f)

            // 정확도
            g.renderColor = RenderColor.of(120, 180, 255)
            g.drawStringRight("%.2f%%".format(entry.accuracy * 100f), (px + pw - 24).toFloat(), ry + rowH - 22f)

            // 구분선
            if (i < entries.lastIndex) {
                g.renderColor = RenderColor.of(50, 35, 80, 80)
                g.drawLine(px + 20, ry + rowH - 1, px + pw - 20, ry + rowH - 1)
            }
        }

        if (entries.isEmpty()) {
            g.font = nameFont
            g.renderColor = RenderColor.of(120, 110, 150)
            g.drawStringCentered("결과 없음", w / 2f, listY + 36f)
        }

        // 하단 힌트
        g.font = hintFont
        g.renderColor = RenderColor.of(80, 68, 108)
        g.drawStringCentered("Enter / Esc  메인 메뉴로", w / 2f, (py + ph - 20).toFloat())
    }

    override fun keyPressed(key: Int, mods: Int) {
        if (key == Keys.ENTER || key == Keys.ESCAPE) {
            ctx.sceneRouter.navigate(MainMenuScene(ctx))
        }
    }
}
