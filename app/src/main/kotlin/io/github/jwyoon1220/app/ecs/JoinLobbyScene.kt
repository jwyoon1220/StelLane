package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.engine.multiplayer.MultiplayerCacheManager
import io.github.jwyoon1220.engine.multiplayer.MultiplayerManager
import io.github.jwyoon1220.app.multiplayer.MultiplayerPlayScene
import io.github.jwyoon1220.app.multiplayer.SpectatorScene
import io.github.jwyoon1220.core.song.ChartParser
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand
import org.slf4j.LoggerFactory
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import kotlin.math.sin

/**
 * 클라이언트/관전자 접속 화면.
 * - IP 입력 (예: 192.168.0.10 또는 192.168.0.10:7777)
 * - Enter: 접속 시도 → 플레이어 목록 대기 → START 수신 시 게임/관전 진입
 */
class JoinLobbyScene(
    private val ctx: GameContext,
    private val manager: MultiplayerManager,
    private val spectate: Boolean
) : Scene() {

    private val log = LoggerFactory.getLogger(JoinLobbyScene::class.java)

    private val titleFont  = FontLoader.bold(36f)
    private val inputFont  = FontLoader.semiBold(22f)
    private val labelFont  = FontLoader.regular(16f)
    private val playerFont = FontLoader.regular(18f)
    private val hintFont   = FontLoader.light(12f)
    private val statusFont = FontLoader.light(14f)

    private val ipBuffer   = StringBuilder("127.0.0.1")
    private var connected  = false
    private var statusMsg  = ""
    private var time       = 0.0

    override fun enter() {
        super.enter()
        time = 0.0
        connected = false
        statusMsg = ""
        ctx.inputManager.clearEvents()
        register(LobbyRenderSystem())

        manager.localRole = if (spectate) "spectator" else "player"
        manager.onPlayerListUpdated = {
            // 호스트에서 PLAYER_LIST 첫 수신 = WebSocket 연결 성공 확인
            if (!connected) {
                connected = true
                statusMsg = "연결됨. 호스트가 게임을 시작할 때까지 대기 중…"
            }
        }
        manager.onHostDisconnected = {
            connected = false
            statusMsg = "호스트가 연결을 끊었습니다."
        }
        manager.onStartGame = { songRelPath, difficulty, files ->
            Thread {
                // 관전자는 곡 파일이 없어도 되므로 파일 대기/차트 파싱 없이 바로 전환
                if (spectate) {
                    ctx.sceneRouter.navigate(SpectatorScene(ctx, manager))
                    return@Thread
                }
                waitForFiles(files.map { it.sha256 })
                val songEntry = findSongEntry(songRelPath)
                if (songEntry == null) {
                    statusMsg = "곡을 찾을 수 없습니다: $songRelPath"
                    return@Thread
                }
                val diffFile = File(songEntry.songDir, songEntry.song.difficulties[difficulty] ?: return@Thread)
                val chart = runCatching { ChartParser.parseChart(diffFile) }.getOrNull() ?: run {
                    statusMsg = "차트 로드 실패"
                    return@Thread
                }
                ctx.sceneRouter.navigate(MultiplayerPlayScene(ctx, songEntry, chart, manager))
            }.apply { isDaemon = true; start() }
        }
    }

    override fun exit() {
        manager.onPlayerListUpdated = null
        manager.onStartGame = null
        manager.onHostDisconnected = null
        super.exit()
    }

    override fun onUpdate(deltaTime: Double) { time += deltaTime }

    private inner class LobbyRenderSystem : RenderProducer {
        override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit
        override fun produce(world: World, out: MutableList<RenderCommand>) {
            out.add(RenderCommand.LegacyDrawContext { renderContents(this) })
        }
    }

    private fun renderContents(g: io.github.jwyoon1220.engine.DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height
        val t = time.toFloat()

        g.renderColor = RenderColor.of(0, 0, 0, 200)
        g.fillRect(0, 0, w, h)

        val pw = 560; val ph = 420
        val px = (w - pw) / 2; val py = (h - ph) / 2

        g.fillLinearGradient(
            px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(),
            px.toFloat(), py.toFloat(), px.toFloat(), (py + ph).toFloat(),
            RenderColor.of(18, 12, 40, 248), RenderColor.of(10, 6, 26, 248)
        )
        val glowA = (sin(t * 1.5f) * 20 + 80).toInt()
        g.renderColor = RenderColor.of(100, 60, 200, glowA)
        g.drawRoundRect(px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(), 16f)

        val title = if (spectate) "관전 참가" else "방 참가"
        g.font = titleFont
        g.renderColor = RenderColor.of(210, 175, 255)
        g.drawStringCentered(title, w / 2f, py + 52f)

        // IP 입력 필드
        g.font = labelFont
        g.renderColor = RenderColor.of(140, 130, 170)
        g.drawString("호스트 IP (예: 192.168.0.10 또는 1.2.3.4:7777)", (px + 20).toFloat(), py + 85f)

        val fieldX = (px + 20).toFloat(); val fieldY = py + 95f
        val fieldW = (pw - 40).toFloat(); val fieldH = 40f
        g.renderColor = RenderColor.of(30, 20, 60, 220)
        g.fillRoundRect(fieldX, fieldY, fieldW, fieldH, 8f)
        g.renderColor = RenderColor.of(120, 80, 200)
        g.drawRoundRect(fieldX, fieldY, fieldW, fieldH, 8f)

        g.font = inputFont
        g.renderColor = RenderColor.WHITE
        g.drawString(ipBuffer.toString(), fieldX + 10f, fieldY + fieldH - 10f)
        // 커서 깜빡임
        if (sin(t * 4f) > 0) {
            val cursorX = fieldX + 10f + g.measureStringWidth(ipBuffer.toString(), inputFont)
            g.renderColor = RenderColor.WHITE
            g.drawLine(cursorX.toInt(), (fieldY + 8).toInt(), cursorX.toInt(), (fieldY + fieldH - 8).toInt())
        }

        // 상태 메시지
        g.font = statusFont
        g.renderColor = if (connected) RenderColor.of(120, 255, 160) else RenderColor.of(255, 160, 80)
        if (statusMsg.isNotEmpty()) g.drawStringCentered(statusMsg, w / 2f, py + 158f)

        // 플레이어 목록 (연결 후)
        if (connected) {
            g.font = labelFont
            g.renderColor = RenderColor.of(140, 130, 170)
            g.drawString("참가자:", (px + 20).toFloat(), py + 180f)

            val players = manager.remotePlayers.values.toList()
            g.font = playerFont
            players.forEachIndexed { i, p ->
                g.renderColor = RenderColor.of(200, 200, 230)
                g.drawString("• ${p.name}", (px + 30).toFloat(), py + 204f + i * 26f)
            }
            if (players.isEmpty()) {
                g.renderColor = RenderColor.of(100, 90, 130)
                g.drawString("대기 중…", (px + 30).toFloat(), py + 204f)
            }
        }

        g.font = hintFont
        g.renderColor = RenderColor.of(80, 68, 108)
        g.drawStringCentered("문자 입력 IP   Enter: 접속   Backspace: 삭제   Esc: 뒤로", w / 2f, py + ph - 16f)
    }

    override fun keyPressed(key: Int, mods: Int) {
        when {
            key == Keys.ENTER -> connect()
            key == Keys.ESCAPE -> {
                manager.stop()
                ctx.multiplayerManager = null
                ctx.sceneRouter.navigate(MultiplayerMenuScene(ctx))
            }
            key == Keys.BACKSPACE -> if (ipBuffer.isNotEmpty()) ipBuffer.deleteCharAt(ipBuffer.lastIndex)
            // Ctrl+V: 클립보드에서 IP 붙여넣기
            key == Keys.V && Keys.isCtrl(mods) -> {
                runCatching {
                    val text = Toolkit.getDefaultToolkit().systemClipboard
                        .getData(DataFlavor.stringFlavor) as? String ?: return
                    val filtered = text.filter { it.isDigit() || it == '.' || it == ':' }
                    ipBuffer.clear()
                    ipBuffer.append(filtered.take(21))
                }
            }
        }
    }

    override fun keyTyped(codepoint: Int) {
        val ch = codepoint.toChar()
        if ((ch.isDigit() || ch == '.' || ch == ':') && ipBuffer.length < 21) ipBuffer.append(ch)
    }

    private fun connect() {
        val raw = ipBuffer.toString().trim()
        val (host, port) = if (':' in raw) {
            val parts = raw.split(':')
            parts[0] to (parts[1].toIntOrNull() ?: MultiplayerManager.DEFAULT_PORT)
        } else {
            raw to MultiplayerManager.DEFAULT_PORT
        }
        statusMsg = "접속 중…"
        connected = false
        if (spectate) manager.spectate(host, port) else manager.joinGame(host, port)
        // connected=true는 PLAYER_LIST 수신(onPlayerListUpdated) 시 설정됨
    }

    private fun waitForFiles(sha256List: List<String>) {
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            if (sha256List.all { MultiplayerCacheManager.getCachedPath(it) != null }) break
            Thread.sleep(200)
        }
    }

    private fun findSongEntry(songRelPath: String) =
        ctx.songManager.songs.firstOrNull { e ->
            val title = e.song.title
            songRelPath.startsWith(title)
        }
}
