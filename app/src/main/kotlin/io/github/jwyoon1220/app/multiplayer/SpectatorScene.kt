package io.github.jwyoon1220.app.multiplayer

import io.github.jwyoon1220.engine.multiplayer.MultiplayerManager
import io.github.jwyoon1220.engine.multiplayer.RemotePlayerState
import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.ecs.MainMenuScene
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * 관전자 분할 화면 씬.
 *
 * 플레이어 수 N에 따라 격자 셀을 계산하고, 각 셀에 미니 레인 + 점수/정확도를 표시.
 * ←→ 로 포커스 플레이어 이동, Enter로 해당 플레이어 풀스크린 확대 토글.
 */
class SpectatorScene(
    private val ctx: GameContext,
    private val manager: MultiplayerManager
) : Scene() {

    private var focusIdx = 0
    private var fullscreen = false
    private var time = 0.0

    private val LANE_COLORS = arrayOf(
        RenderColor.of(120, 80, 255),
        RenderColor.of(80, 160, 255),
        RenderColor.of(255, 200, 60),
        RenderColor.of(255, 100, 120)
    )

    private inner class SpectatorRenderSystem : RenderProducer {
        private val nameFont   = FontLoader.bold(14f)
        private val scoreFont  = FontLoader.semiBold(12f)
        private val accFont    = FontLoader.light(11f)
        private val titleFont  = FontLoader.bold(20f)
        private val hintFont   = FontLoader.light(11f)

        override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit

        override fun produce(world: World, out: MutableList<RenderCommand>) {
            out.add(RenderCommand.LegacyDrawContext { renderContents(this) })
        }

        private fun renderContents(g: io.github.jwyoon1220.engine.DrawContext) {
            val t = time.toFloat()
            val W = g.clipBounds.width
            val H = g.clipBounds.height

            g.renderColor = RenderColor.of(8, 5, 20)
            g.fillRect(0, 0, W, H)

            val players = manager.remotePlayers.values.filter { it.role == "player" }
            if (players.isEmpty()) {
                g.font = titleFont
                g.renderColor = RenderColor.of(140, 130, 170)
                g.drawStringCentered("플레이어 없음 — 대기 중…", W / 2f, H / 2f)
                return
            }

            val focusPlayer = players.getOrNull(focusIdx)

            if (fullscreen && focusPlayer != null) {
                renderPlayerCell(g, focusPlayer, 0f, 0f, W.toFloat(), H.toFloat(), true, t)
            } else {
                val N    = players.size
                val cols = ceil(sqrt(N.toDouble())).toInt().coerceAtLeast(1)
                val rows = ceil(N.toDouble() / cols).toInt()
                val cellW = W.toFloat() / cols
                val cellH = H.toFloat() / rows

                players.forEachIndexed { i, player ->
                    val col = i % cols
                    val row = i / cols
                    val cx  = col * cellW
                    val cy  = row * cellH
                    val isFocus = i == focusIdx
                    renderPlayerCell(g, player, cx, cy, cellW, cellH, false, t)

                    if (isFocus) {
                        val bA = (sin(t * 2.5f) * 30 + 160).toInt()
                        g.renderColor = RenderColor.of(200, 130, 255, bA)
                        g.drawRoundRect(cx + 2f, cy + 2f, cellW - 4f, cellH - 4f, 6f)
                    }
                }
            }

            g.renderColor = RenderColor.of(0, 0, 0, 120)
            g.fillRect(0, H - 22, W, 22)
            g.font = hintFont
            g.renderColor = RenderColor.of(100, 90, 130)
            g.drawStringCentered("←→ 포커스 이동   Enter: 확대/축소   Esc: 나가기", W / 2f, H - 6f)
        }

        private fun renderPlayerCell(
            g: io.github.jwyoon1220.engine.DrawContext,
            player: RemotePlayerState,
            cx: Float, cy: Float, cellW: Float, cellH: Float,
            large: Boolean, t: Float
        ) {
            val pad = if (large) 12f else 6f

            g.renderColor = RenderColor.of(14, 8, 30, 230)
            g.fillRoundRect(cx + 1f, cy + 1f, cellW - 2f, cellH - 2f, 6f)

            val nameH = if (large) 32f else 22f
            val scoreH = if (large) 26f else 18f
            val laneH = cellH - nameH - scoreH - pad * 3

            g.font = if (large) titleFont else nameFont
            g.renderColor = RenderColor.of(220, 200, 255)
            g.drawString(player.name.take(12), cx + pad, cy + nameH - 2f)

            val laneAreaX = cx + pad
            val laneAreaY = cy + nameH + pad
            val laneAreaW = cellW - pad * 2
            val laneW = laneAreaW / 4f

            for (lane in 0..3) {
                val lx = laneAreaX + lane * laneW
                val held = player.laneHeld.getOrElse(lane) { false }

                g.renderColor = RenderColor.of(20, 14, 40)
                g.fillRoundRect(lx + 1f, laneAreaY, laneW - 2f, laneH, 3f)

                if (held) {
                    val col = LANE_COLORS[lane]
                    g.renderColor = RenderColor.of(col.r, col.g, col.b, 160)
                    g.fillRoundRect(lx + 1f, laneAreaY, laneW - 2f, laneH, 3f)
                }

                g.renderColor = RenderColor.of(50, 40, 80)
                g.drawLine(lx.toInt(), laneAreaY.toInt(), lx.toInt(), (laneAreaY + laneH).toInt())
            }

            val infoY = laneAreaY + laneH + pad + scoreH - 4f
            g.font = scoreFont
            g.renderColor = RenderColor.of(200, 255, 200)
            g.drawString("%,d".format(player.score), cx + pad, infoY)

            g.font = accFont
            g.renderColor = RenderColor.of(180, 180, 220)
            g.drawStringRight("%.1f%%".format(player.accuracy * 100f), cx + cellW - pad, infoY)

            g.font = accFont
            g.renderColor = RenderColor.of(100, 200, 255)
            g.drawString("P:${player.counts.getOrElse(0){0}}", cx + pad, infoY + 14f)
        }
    }

    override fun enter() {
        super.enter()
        focusIdx = 0; fullscreen = false; time = 0.0
        manager.onGameOver = { entries ->
            ctx.sceneRouter.navigate(MultiplayerResultScene(ctx, entries, manager))
        }
        manager.onHostDisconnected = {
            manager.stop()
            ctx.multiplayerManager = null
            ctx.sceneRouter.navigate(MainMenuScene(ctx))
        }
        register(SpectatorRenderSystem())
    }

    override fun exit() {
        manager.onGameOver = null
        manager.onHostDisconnected = null
        super.exit()
    }

    override fun onUpdate(deltaTime: Double) { time += deltaTime }

    override fun keyPressed(key: Int, mods: Int) {
        val players = manager.remotePlayers.values.filter { it.role == "player" }
        when (key) {
            Keys.LEFT  -> focusIdx = (focusIdx - 1 + players.size).coerceAtLeast(0) % players.size.coerceAtLeast(1)
            Keys.RIGHT -> focusIdx = (focusIdx + 1) % players.size.coerceAtLeast(1)
            Keys.ENTER -> fullscreen = !fullscreen
            Keys.ESCAPE -> {
                manager.onGameOver = null
                manager.stop()
                ctx.multiplayerManager = null
                ctx.sceneRouter.navigate(MainMenuScene(ctx))
            }
        }
    }
}
