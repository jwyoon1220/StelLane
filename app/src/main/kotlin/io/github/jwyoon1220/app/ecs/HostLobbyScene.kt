package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.multiplayer.MultiplayerManager
import io.github.jwyoon1220.app.multiplayer.MultiplayerPlayScene
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.song.ChartParser
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.render.RenderColor
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.sin

/**
 * 호스트 로비 화면.
 * - 서버를 시작하고 로컬/공인 IP를 표시
 * - 접속한 플레이어 목록 실시간 표시
 * - Space: 곡 선택 → 선택 완료 시 START 브로드캐스트 후 게임 진입
 */
class HostLobbyScene(
    private val ctx: GameContext,
    private val manager: MultiplayerManager
) : Scene() {

    private val log = LoggerFactory.getLogger(HostLobbyScene::class.java)

    private val titleFont  = FontLoader.bold(36f)
    private val ipFont     = FontLoader.semiBold(20f)
    private val labelFont  = FontLoader.regular(16f)
    private val playerFont = FontLoader.regular(18f)
    private val hintFont   = FontLoader.light(12f)

    private var displayIp   = "탐색 중..."
    private var upnpSuccess = false
    private var time        = 0.0

    // 곡 선택 후 받은 값
    private var pendingEntry: SongEntry? = null
    private var pendingChart: Chart?     = null
    private var pendingDiff: String      = ""

    override fun enter() {
        super.enter()
        time = 0.0
        manager.localRole = "player"
        manager.hostGame(MultiplayerManager.DEFAULT_PORT)
        manager.onPlayerListUpdated = {}

        // UPnP 시도 → 결과를 displayIp에 반영
        manager.tryUPnP(MultiplayerManager.DEFAULT_PORT) { ip, success ->
            displayIp   = "$ip:${MultiplayerManager.DEFAULT_PORT}"
            upnpSuccess = success
        }
        displayIp = "${manager.getLocalIp()}:${MultiplayerManager.DEFAULT_PORT} (로컬)"
        ctx.inputManager.clearEvents()
    }

    override fun exit() {
        manager.onPlayerListUpdated = null
        super.exit()
    }

    override fun onUpdate(deltaTime: Double) { time += deltaTime }

    override fun render(g: DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height
        val t = time.toFloat()

        g.renderColor = RenderColor.of(0, 0, 0, 200)
        g.fillRect(0, 0, w, h)

        val pw = 620; val ph = 480
        val px = (w - pw) / 2; val py = (h - ph) / 2

        g.fillLinearGradient(
            px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(),
            px.toFloat(), py.toFloat(), px.toFloat(), (py + ph).toFloat(),
            RenderColor.of(18, 12, 40, 248), RenderColor.of(10, 6, 26, 248)
        )
        val glowA = (sin(t * 1.5f) * 20 + 80).toInt()
        g.renderColor = RenderColor.of(100, 60, 200, glowA)
        g.drawRoundRect(px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(), 16f)

        g.font = titleFont
        g.renderColor = RenderColor.of(210, 175, 255)
        g.drawStringCentered("방 만들기", w / 2f, py + 52f)

        // IP 표시
        g.font = labelFont
        g.renderColor = RenderColor.of(140, 130, 170)
        g.drawStringCentered("친구에게 아래 주소를 알려주세요", w / 2f, py + 80f)

        val ipColor = if (upnpSuccess) RenderColor.of(120, 255, 160) else RenderColor.of(255, 210, 80)
        g.font = ipFont
        g.renderColor = ipColor
        g.drawStringCentered(displayIp, w / 2f, py + 108f)
        if (!upnpSuccess) {
            g.font = hintFont
            g.renderColor = RenderColor.of(160, 120, 80)
            g.drawStringCentered("(포트 ${MultiplayerManager.DEFAULT_PORT} 수동 포트포워딩 필요할 수 있음)", w / 2f, py + 126f)
        }

        // 플레이어 목록
        g.font = labelFont
        g.renderColor = RenderColor.of(140, 130, 170)
        g.drawString("참가자:", (px + 20).toFloat(), py + 155f)

        val players = manager.remotePlayers.values.toList()
        g.font = playerFont
        players.forEachIndexed { i, p ->
            g.renderColor = if (p.role == "spectator") RenderColor.of(140, 180, 220) else RenderColor.of(200, 200, 230)
            g.drawString("• ${p.name} (${if (p.role == "spectator") "관전" else "플레이어"})",
                (px + 30).toFloat(), py + 180f + i * 28f)
        }
        if (players.isEmpty()) {
            g.renderColor = RenderColor.of(100, 90, 130)
            g.drawString("대기 중…", (px + 30).toFloat(), py + 180f)
        }

        // 힌트
        g.font = hintFont
        g.renderColor = RenderColor.of(80, 68, 108)
        g.drawStringCentered("Space: 곡 선택 후 게임 시작   Esc: 뒤로", w / 2f, py + ph - 16f)
    }

    override fun keyPressed(key: Int, mods: Int) {
        when (key) {
            Keys.SPACE   -> ctx.sceneRouter.navigate(
                SongSelectScene(ctx, SelectMode.MULTIPLAYER_HOST) { entry, chart, diff ->
                    onSongSelected(entry, chart, diff)
                }
            )
            Keys.ESCAPE  -> {
                manager.stop()
                ctx.multiplayerManager = null
                ctx.sceneRouter.navigate(MultiplayerMenuScene(ctx))
            }
        }
    }

    /** SongSelectScene이 곡 선택 완료 후 호출. START 브로드캐스트 후 게임 진입. */
    fun onSongSelected(entry: SongEntry, chart: Chart, difficulty: String) {
        manager.setPendingSongDir(entry.songDir)
        manager.broadcastStart(entry.songDir, entry.song.title + "/" + difficulty, difficulty)
        ctx.sceneRouter.navigate(MultiplayerPlayScene(ctx, entry, chart, manager))
    }
}
