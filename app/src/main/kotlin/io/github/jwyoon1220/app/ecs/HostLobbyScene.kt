package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.resolveMediaPath
import io.github.jwyoon1220.engine.multiplayer.MultiplayerManager
import io.github.jwyoon1220.app.multiplayer.MultiplayerPlayScene
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.data.Chart
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
import java.awt.datatransfer.StringSelection
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
    private var ipVisible   = false
    private var copyFlash   = 0f   // 복사 완료 플래시 타이머 (초)
    private var time        = 0.0

    // 곡 선택 후 받은 값
    private var pendingEntry: SongEntry? = null
    private var pendingChart: Chart?     = null
    private var pendingDiff: String      = ""

    override fun enter() {
        super.enter()
        time = 0.0
        manager.localRole = "player"
        // 서버가 이미 실행 중이면(SongSelect에서 ESC로 돌아온 경우) 재시작하지 않음
        if (!manager.isServerRunning) {
            manager.hostGame(MultiplayerManager.DEFAULT_PORT)
        }
        manager.onPlayerListUpdated = {}
        // 오프셋 보정 대기 UI는 MultiplayerPlayScene 자체에서 처리(배경 영상이 자연스럽게 보이도록) —
        // 곡 선택 즉시 이 콜백이 곧바로 플레이 화면으로 진입시킨다.
        manager.onCalibrateStart = { _, _, _, hlsUrl ->
            val entry = pendingEntry; val chart = pendingChart
            if (entry != null && chart != null) {
                ctx.sceneRouter.navigate(MultiplayerPlayScene(ctx, entry, chart, manager, hlsUrl, entry.resolveMediaPath()))
            }
        }

        // UPnP 시도 → 결과를 displayIp에 반영
        manager.tryUPnP(MultiplayerManager.DEFAULT_PORT) { ip, success ->
            displayIp   = "$ip:${MultiplayerManager.DEFAULT_PORT}"
            upnpSuccess = success
        }
        displayIp = "${manager.getLocalIp()}:${MultiplayerManager.DEFAULT_PORT} (로컬)"
        ipVisible = false
        copyFlash = 0f
        ctx.inputManager.clearEvents()
        register(LobbyRenderSystem())
    }

    override fun exit() {
        manager.onPlayerListUpdated = null
        // onCalibrateStart는 여기서 null로 지우면 안 됨 — Space로 SongSelectScene에 갔다가
        // 곡을 고르면 그 시점에 이 씬은 이미 exit()된 상태지만 beginCalibration()이 여전히
        // 이 콜백을 호출해 MultiplayerPlayScene으로 진입시켜야 하기 때문. 매니저를 완전히
        // 정리할 때(ESC로 방을 나갈 때 manager.stop() + ctx.multiplayerManager = null)는
        // 매니저 자체가 버려지므로 콜백이 남아있어도 문제없음.
        super.exit()
    }

    override fun onUpdate(deltaTime: Double) {
        time += deltaTime
        if (copyFlash > 0f) copyFlash = (copyFlash - deltaTime.toFloat()).coerceAtLeast(0f)
    }

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
        val maskedIp = "●".repeat(displayIp.length.coerceAtMost(20))
        g.drawStringCentered(if (ipVisible) displayIp else maskedIp, w / 2f, py + 108f)
        if (!upnpSuccess) {
            g.font = hintFont
            g.renderColor = RenderColor.of(160, 120, 80)
            g.drawStringCentered("(포트 ${MultiplayerManager.DEFAULT_PORT} 수동 포트포워딩 필요할 수 있음)", w / 2f, py + 126f)
        }

        // 보이기/숨기기 버튼 + 복사 버튼
        val btnY    = py + 138f
        val btnH    = 26f
        val btnW    = 88f
        val gapBtn  = 10f
        val totalBtnW = btnW * 2 + gapBtn
        val btn1X   = w / 2f - totalBtnW / 2f
        val btn2X   = btn1X + btnW + gapBtn

        // 보이기/숨기기
        g.renderColor = RenderColor.of(50, 35, 90, 200)
        g.fillRoundRect(btn1X, btnY, btnW, btnH, 6f)
        g.renderColor = RenderColor.of(120, 80, 200)
        g.drawRoundRect(btn1X, btnY, btnW, btnH, 6f)
        g.font = hintFont
        g.renderColor = RenderColor.of(200, 180, 240)
        g.drawStringCentered(if (ipVisible) "숨기기" else "보이기", btn1X + btnW / 2f, btnY + btnH - 8f)

        // 복사
        val copyHighlight = copyFlash > 0f
        g.renderColor = if (copyHighlight) RenderColor.of(40, 140, 80, 220) else RenderColor.of(50, 35, 90, 200)
        g.fillRoundRect(btn2X, btnY, btnW, btnH, 6f)
        g.renderColor = if (copyHighlight) RenderColor.of(80, 220, 120) else RenderColor.of(120, 80, 200)
        g.drawRoundRect(btn2X, btnY, btnW, btnH, 6f)
        g.font = hintFont
        g.renderColor = if (copyHighlight) RenderColor.of(120, 255, 160) else RenderColor.of(200, 180, 240)
        g.drawStringCentered(if (copyHighlight) "복사됨!" else "복사", btn2X + btnW / 2f, btnY + btnH - 8f)

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

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        val pw = 620; val ph = 480
        val px = (1280 - pw) / 2
        val btnY   = (720 - ph) / 2 + 138f
        val btnH   = 26f
        val btnW   = 88f
        val btn1X  = 1280 / 2f - (btnW * 2 + 10f) / 2f
        val btn2X  = btn1X + btnW + 10f

        if (y in btnY..(btnY + btnH)) {
            when {
                x in btn1X..(btn1X + btnW) -> ipVisible = !ipVisible
                x in btn2X..(btn2X + btnW) -> {
                    runCatching {
                        Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(StringSelection(displayIp), null)
                    }
                    copyFlash = 1.5f
                }
            }
        }
    }

    override fun keyPressed(key: Int, mods: Int) {
        when (key) {
            Keys.SPACE   -> {
                val self = this
                ctx.sceneRouter.navigate(
                    SongSelectScene(
                        ctx,
                        SelectMode.MULTIPLAYER_HOST,
                        onMultiplayerConfirm = { entry, chart, diff -> onSongSelected(entry, chart, diff) },
                        onCancel = { ctx.sceneRouter.navigate(self) }
                    )
                )
            }
            Keys.ESCAPE  -> {
                manager.stop()
                ctx.multiplayerManager = null
                ctx.sceneRouter.navigate(MultiplayerMenuScene(ctx))
            }
        }
    }

    /**
     * SongSelectScene이 곡 선택 완료 후 호출. beginCalibration()이 onCalibrateStart를 즉시(로컬)
     * 호출하므로 곧바로 MultiplayerPlayScene(오프셋 보정 대기 UI 포함)으로 진입한다.
     */
    fun onSongSelected(entry: SongEntry, chart: Chart, difficulty: String) {
        pendingEntry = entry; pendingChart = chart; pendingDiff = difficulty
        manager.setPendingSongDir(entry.songDir)
        manager.beginCalibration(entry.songDir, entry.song.title + "/" + difficulty, difficulty)
    }
}
