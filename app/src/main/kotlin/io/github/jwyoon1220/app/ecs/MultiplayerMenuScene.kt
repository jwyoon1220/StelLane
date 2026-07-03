package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.engine.multiplayer.MultiplayerManager
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand
import kotlin.math.sin

/** 멀티플레이어 진입 메뉴: 방 만들기 / 방 참가 / 관전. */
class MultiplayerMenuScene(private val ctx: GameContext) : Scene() {

    private val titleFont = FontLoader.bold(44f)
    private val itemFont  = FontLoader.semiBold(26f)
    private val descFont  = FontLoader.light(14f)
    private val hintFont  = FontLoader.light(12f)

    private val items = listOf("방 만들기", "방 참가", "관전")
    private val descs = listOf(
        "WebSocket 서버를 열고 친구를 초대합니다",
        "호스트의 IP:포트를 입력해 참가합니다",
        "진행 중인 방에 관전자로 참가합니다"
    )
    private var cursor = 0
    private var time   = 0.0

    private inner class MenuRenderSystem : RenderProducer {
        override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit
        override fun produce(world: World, out: MutableList<RenderCommand>) {
            out.add(RenderCommand.LegacyDrawContext { renderContents(this) })
        }

        private fun renderContents(g: io.github.jwyoon1220.engine.DrawContext) {
            val t = time.toFloat()
            val w = g.clipBounds.width; val h = g.clipBounds.height

            g.renderColor = RenderColor.of(0, 0, 0, 200); g.fillRect(0, 0, w, h)
            val pw = 560; val ph = 380; val px = (w - pw) / 2; val py = (h - ph) / 2

            g.fillLinearGradient(px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(),
                px.toFloat(), py.toFloat(), px.toFloat(), (py + ph).toFloat(),
                RenderColor.of(18, 12, 40, 248), RenderColor.of(10, 6, 26, 248))
            val glowA = (sin(t * 1.5f) * 20 + 80).toInt()
            g.renderColor = RenderColor.of(100, 60, 200, glowA)
            g.drawRoundRect(px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(), 16f)

            g.font = titleFont; g.renderColor = RenderColor.of(210, 175, 255)
            g.drawStringCentered("멀티플레이어", w / 2f, py + 56f)

            val startY = py + 90; val rowH = 80
            items.forEachIndexed { i, label ->
                val selected = i == cursor; val iy = startY + i * rowH
                if (selected) {
                    g.fillLinearGradient((px + 12).toFloat(), iy.toFloat(), (pw - 24).toFloat(), rowH.toFloat(),
                        (px + 12).toFloat(), 0f, (px + pw - 12).toFloat(), 0f,
                        RenderColor.of(70, 35, 150, 100), RenderColor.of(40, 20, 80, 40))
                    val bA = (sin(t * 2f) * 20 + 80).toInt()
                    g.renderColor = RenderColor.of(120, 70, 220, bA)
                    g.drawRoundRect((px + 12).toFloat(), iy.toFloat(), (pw - 24).toFloat(), (rowH - 8).toFloat(), 10f)
                    g.renderColor = RenderColor.of(170, 100, 255)
                    g.fillRoundRect((px + 12).toFloat(), (iy + 10).toFloat(), 3f, (rowH - 28).toFloat(), 2f)
                }
                g.font = itemFont
                g.renderColor = if (selected) RenderColor.of(255, 230, 100) else RenderColor.of(160, 140, 200)
                g.drawString(label, (px + 30).toFloat(), iy + 34f)
                g.font = descFont; g.renderColor = RenderColor.of(110, 100, 140)
                g.drawString(descs[i], (px + 30).toFloat(), iy + 56f)
            }

            g.font = hintFont; g.renderColor = RenderColor.of(80, 68, 108)
            g.drawStringCentered("↑↓ 선택   Enter 진입   Esc 뒤로", w / 2f, py + ph - 16f)
        }
    }

    override fun enter() {
        super.enter()
        cursor = 0; time = 0.0
        register(MenuRenderSystem())
    }

    override fun onUpdate(deltaTime: Double) { time += deltaTime }

    override fun keyPressed(key: Int, mods: Int) {
        when (key) {
            Keys.UP    -> cursor = (cursor - 1 + items.size) % items.size
            Keys.DOWN  -> cursor = (cursor + 1) % items.size
            Keys.ENTER -> onSelect()
            Keys.ESCAPE -> ctx.sceneRouter.navigate(MainMenuScene(ctx))
        }
    }

    private fun onSelect() {
        val manager = MultiplayerManager().also { it.localPlayerName = io.github.jwyoon1220.app.AppSettings.nickname }
        ctx.multiplayerManager = manager
        when (cursor) {
            0 -> ctx.sceneRouter.navigate(HostLobbyScene(ctx, manager))
            1 -> ctx.sceneRouter.navigate(JoinLobbyScene(ctx, manager, spectate = false))
            2 -> ctx.sceneRouter.navigate(JoinLobbyScene(ctx, manager, spectate = true))
        }
    }
}
