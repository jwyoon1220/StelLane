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

    private val titleFont  = FontLoader.bold(44f)
    private val itemFont   = FontLoader.semiBold(26f)
    private val descFont   = FontLoader.light(14f)
    private val hintFont   = FontLoader.light(12f)
    private val modalFont  = FontLoader.semiBold(18f)
    private val modalBody  = FontLoader.regular(13f)
    private val btnFont    = FontLoader.semiBold(14f)

    private val items = listOf("방 만들기", "방 참가", "관전")
    private val descs = listOf(
        "WebSocket 서버를 열고 친구를 초대합니다",
        "호스트의 IP:포트를 입력해 참가합니다",
        "진행 중인 방에 관전자로 참가합니다"
    )
    private var cursor = 0
    private var time   = 0.0

    // 저작권 동의 모달 상태 (방 만들기 전용)
    private var showCopyrightModal = false
    private var copyrightChecked   = false

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

            if (showCopyrightModal) renderCopyrightModal(g, w, h)
        }
    }

    private fun renderCopyrightModal(g: io.github.jwyoon1220.engine.DrawContext, w: Int, h: Int) {
        // 배경 딤
        g.renderColor = RenderColor.of(0, 0, 0, 160)
        g.fillRect(0, 0, w, h)

        val mw = 580; val mh = 260
        val mx = (w - mw) / 2; val my = (h - mh) / 2

        g.fillLinearGradient(mx.toFloat(), my.toFloat(), mw.toFloat(), mh.toFloat(),
            mx.toFloat(), my.toFloat(), mx.toFloat(), (my + mh).toFloat(),
            RenderColor.of(20, 10, 44, 252), RenderColor.of(12, 6, 28, 252))
        g.renderColor = RenderColor.of(140, 80, 220, 180)
        g.drawRoundRect(mx.toFloat(), my.toFloat(), mw.toFloat(), mh.toFloat(), 14f)

        g.font = modalFont
        g.renderColor = RenderColor.of(220, 190, 255)
        g.drawStringCentered("저작권 동의 확인", w / 2f, my + 36f)

        g.font = modalBody
        g.renderColor = RenderColor.of(170, 160, 200)
        g.drawStringCentered("방을 개설하면 선택한 음원·영상이 참가자에게 스트리밍될 수 있습니다.", w / 2f, my + 68f)

        // 체크박스
        val cbX = mx + 32f; val cbY = my + 96f; val cbS = 18f
        g.renderColor = if (copyrightChecked) RenderColor.of(120, 70, 220) else RenderColor.of(50, 40, 80)
        g.fillRoundRect(cbX, cbY, cbS, cbS, 4f)
        g.renderColor = RenderColor.of(140, 100, 220)
        g.drawRoundRect(cbX, cbY, cbS, cbS, 4f)
        if (copyrightChecked) {
            g.renderColor = RenderColor.of(230, 210, 255)
            g.drawString("✓", cbX + 2f, cbY + cbS - 3f)
        }

        g.font = modalBody
        g.renderColor = RenderColor.of(190, 175, 220)
        g.drawString(
            "저작권이 없는 음원의 무단 공유 시 모든 책임은 본인에게 있음에 동의합니다.",
            cbX + cbS + 10f, cbY + cbS - 3f
        )

        g.font = hintFont
        g.renderColor = RenderColor.of(100, 90, 130)
        g.drawStringCentered("체크박스를 클릭하거나 Space 키로 선택/해제할 수 있습니다.", w / 2f, my + 140f)

        // [확인] 버튼
        val okW = 110f; val okH = 34f
        val okX = w / 2f + 8f; val okY = (my + mh - 54).toFloat()
        if (copyrightChecked) {
            g.renderColor = RenderColor.of(55, 28, 120, 230)
            g.fillRoundRect(okX, okY, okW, okH, 8f)
            g.renderColor = RenderColor.of(130, 75, 240)
            g.drawRoundRect(okX, okY, okW, okH, 8f)
            g.font = btnFont
            g.renderColor = RenderColor.of(225, 210, 255)
            g.drawStringCentered("확인", okX + okW / 2f, okY + okH - 10f)
        } else {
            g.renderColor = RenderColor.of(28, 22, 52, 140)
            g.fillRoundRect(okX, okY, okW, okH, 8f)
            g.renderColor = RenderColor.of(55, 45, 85, 120)
            g.drawRoundRect(okX, okY, okW, okH, 8f)
            g.font = btnFont
            g.renderColor = RenderColor.of(85, 75, 115)
            g.drawStringCentered("확인", okX + okW / 2f, okY + okH - 10f)
        }

        // [취소] 버튼
        val cancelW = 110f; val cancelH = 34f
        val cancelX = w / 2f - cancelW - 8f; val cancelY = (my + mh - 54).toFloat()
        g.renderColor = RenderColor.of(38, 18, 38, 180)
        g.fillRoundRect(cancelX, cancelY, cancelW, cancelH, 8f)
        g.renderColor = RenderColor.of(110, 55, 85)
        g.drawRoundRect(cancelX, cancelY, cancelW, cancelH, 8f)
        g.font = btnFont
        g.renderColor = RenderColor.of(175, 135, 155)
        g.drawStringCentered("취소", cancelX + cancelW / 2f, cancelY + cancelH - 10f)
    }

    override fun enter() {
        super.enter()
        cursor = 0; time = 0.0
        showCopyrightModal = false; copyrightChecked = false
        register(MenuRenderSystem())
    }

    override fun onUpdate(deltaTime: Double) { time += deltaTime }

    override fun keyPressed(key: Int, mods: Int) {
        if (showCopyrightModal) {
            when (key) {
                Keys.SPACE  -> copyrightChecked = !copyrightChecked
                Keys.ENTER  -> if (copyrightChecked) confirmHosting()
                Keys.ESCAPE -> { showCopyrightModal = false; copyrightChecked = false }
            }
            return
        }
        when (key) {
            Keys.UP     -> cursor = (cursor - 1 + items.size) % items.size
            Keys.DOWN   -> cursor = (cursor + 1) % items.size
            Keys.ENTER  -> onSelect()
            Keys.ESCAPE -> ctx.sceneRouter.navigate(MainMenuScene(ctx))
        }
    }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        if (!showCopyrightModal) return

        val w = 1280; val h = 720
        val mw = 580; val mh = 260
        val mx = (w - mw) / 2; val my = (h - mh) / 2

        // 체크박스 클릭
        val cbX = mx + 32f; val cbY = my + 96f; val cbS = 18f
        if (x in cbX..(cbX + cbS) && y in cbY..(cbY + cbS)) {
            copyrightChecked = !copyrightChecked
            return
        }

        // [확인] 클릭
        val okW = 110f; val okH = 34f
        val okX = w / 2f + 8f; val okY = (my + mh - 54).toFloat()
        if (copyrightChecked && x in okX..(okX + okW) && y in okY..(okY + okH)) {
            confirmHosting()
            return
        }

        // [취소] 클릭
        val cancelW = 110f; val cancelH = 34f
        val cancelX = w / 2f - cancelW - 8f; val cancelY = (my + mh - 54).toFloat()
        if (x in cancelX..(cancelX + cancelW) && y in cancelY..(cancelY + cancelH)) {
            showCopyrightModal = false; copyrightChecked = false
        }
    }

    private fun onSelect() {
        when (cursor) {
            0 -> { showCopyrightModal = true; copyrightChecked = false }
            else -> {
                val manager = MultiplayerManager().also { it.localPlayerName = io.github.jwyoon1220.app.AppSettings.nickname }
                ctx.multiplayerManager = manager
                when (cursor) {
                    1 -> ctx.sceneRouter.navigate(JoinLobbyScene(ctx, manager, spectate = false))
                    2 -> ctx.sceneRouter.navigate(JoinLobbyScene(ctx, manager, spectate = true))
                }
            }
        }
    }

    private fun confirmHosting() {
        showCopyrightModal = false
        copyrightChecked   = false
        val manager = MultiplayerManager().also { it.localPlayerName = io.github.jwyoon1220.app.AppSettings.nickname }
        ctx.multiplayerManager = manager
        ctx.sceneRouter.navigate(HostLobbyScene(ctx, manager))
    }
}
