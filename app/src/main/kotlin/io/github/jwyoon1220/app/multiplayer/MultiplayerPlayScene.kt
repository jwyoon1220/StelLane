package io.github.jwyoon1220.app.multiplayer

import io.github.jwyoon1220.engine.multiplayer.MultiplayerManager
import io.github.jwyoon1220.engine.multiplayer.RemotePlayerState
import io.github.jwyoon1220.engine.multiplayer.VideoSyncCoordinator
import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.ecs.MainMenuScene
import io.github.jwyoon1220.app.ecs.PlayScene
import io.github.jwyoon1220.app.resolveMediaPath
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.engine.OpenGLRenderable
import io.github.jwyoon1220.engine.GlEffectProvider
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderCommand
import io.github.jwyoon1220.engine.GlQuadBatchRenderer
import io.github.jwyoon1220.engine.GlScreenEffectData
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.render.RenderColor
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * PlayScene을 위임(wrap)하여 멀티플레이어 HUD를 추가하는 씬.
 *
 * PlayScene 자체는 수정하지 않으며, 이 래퍼가:
 * - 왼쪽 위: 실시간 순위 (1위~10위) — 보정 중에는 오프셋 조정 대기 패널이 대신 표시됨
 * - 오른쪽 위: 곡 진행도 (1:23 / 3:45)
 * - update() 후 점수 변화를 감지해 네트워크 스레드 큐에 넣음
 *
 * 오프셋 보정은 이 씬 자체에서 진행합니다(로비 화면이 아님) — PlayScene은 영상을 배경으로
 * 자연스럽게 보여주도록 설계되어 있어(옅은 오버레이), 로비 화면의 불투명 배경과 달리 테스트
 * 영상이 실제로 보입니다. [calibrationHlsUrl]로 테스트 스트림을 재생하며 클럭 핑퐁 + 실측
 * 시작 지연을 측정하고, 완료되면 ReadyMsg를 보냅니다. 호스트가 전원 준비를 확인하고 최종
 * sync_epoch_ms를 보내오면([MultiplayerManager.onStartGame]) PlayScene의 READY 게이트가
 * 열려 정상적인 6초 카운트다운(2초 대기 + 3,2,1,GO) 후 실제 매치 영상([matchVideoLocalPath],
 * 전체 파일 전송으로 이미 로컬/캐시에 확보된 경로)으로 전환됩니다. 영상 자체는 스트리밍하지
 * 않지만 재생 시작 시점만은 sync_epoch_ms에 맞춰 스케줄링해 클라이언트 간 동기화를 유지합니다.
 */
class MultiplayerPlayScene(
    private val ctx: GameContext,
    private val songEntry: SongEntry,
    chart: Chart,
    private val manager: MultiplayerManager,
    private val calibrationHlsUrl: String = "",
    private val matchVideoLocalPath: String? = null
) : Scene(), OpenGLRenderable, GlEffectProvider {

    private val log = LoggerFactory.getLogger(MultiplayerPlayScene::class.java)
    private val inner = PlayScene(ctx, songEntry, chart)
    private val totalNotes = chart.notes.size
    private val totalMs: Long = chart.notes.maxOfOrNull { it.endTime ?: it.time } ?: 1L
    private val syncCoordinator = VideoSyncCoordinator(manager)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mp-play-media-scheduler").apply { isDaemon = true }
    }

    // ── 오프셋 보정 상태 ─────────────────────────────────────────────────────────
    @Volatile private var calibrationDone = false
    @Volatile private var finalSyncReceived = false
    @Volatile private var matchSyncEpochMs = 0L
    @Volatile private var measuredStartupLatencyMs = 0L

    // HUD 폰트
    private val rankFont    = FontLoader.semiBold(16f)
    private val rankNumFont = FontLoader.bold(18f)
    private val timeFont    = FontLoader.semiBold(18f)
    private val barFont     = FontLoader.light(13f)

    // 이전 프레임 점수 (델타 체크)
    private var prevScore    = -1
    private var prevCounts   = IntArray(4) { -1 }
    private var prevLaneHeld = BooleanArray(4)
    private var finishSent   = false
    // exit()에서 manager.stop()을 생략할 플래그 (관전 전환 시 true)
    private var exitToSpectator = false

    init {
        manager.setTotalNotes(totalNotes)
    }

    // ── GameState 위임 ───────────────────────────────────────────────────────────

    override fun enter() {
        super.enter()
        finishSent = false
        exitToSpectator = false
        calibrationDone = false
        finalSyncReceived = false
        matchSyncEpochMs = 0L
        measuredStartupLatencyMs = 0L

        inner.readyPhaseGate = { calibrationDone && finalSyncReceived }
        inner.calibrationOverlayRenderer = { g -> renderCalibrationWaitPanel(g, manager, calibrationDone) }
        inner.mediaStartOverride = {
            val path = matchVideoLocalPath
            if (path == null || matchSyncEpochMs <= 0) {
                songEntry.resolveMediaPath()?.let { ctx.videoBackground.play(it) }
            } else {
                val delay = syncCoordinator.computeScheduleDelayMs(matchSyncEpochMs, measuredStartupLatencyMs)
                scheduler.schedule({ ctx.videoBackground.play(path) }, delay, TimeUnit.MILLISECONDS)
            }
        }
        inner.enter()

        // 보정 중 테스트 영상(짧은 클립)이 먼저 끝나면 처음부터 반복 재생. 게이트가 열린 뒤(실제
        // 매치 영상 재생 중)에는 PlayScene의 원래 종료 처리(RESULT 전환)로 위임.
        val originalOnFinished = ctx.videoBackground.onFinished
        ctx.videoBackground.onFinished = {
            if (!(calibrationDone && finalSyncReceived) && calibrationHlsUrl.isNotEmpty()) {
                ctx.videoBackground.play(calibrationHlsUrl)
            } else {
                originalOnFinished?.invoke()
            }
        }

        register(HudRenderSystem())
        // 클라이언트 전용: 호스트가 끊어지면 메인 메뉴로 복귀
        manager.onHostDisconnected = {
            finishSent = true
            exitToSpectator = true
            manager.stop()
            ctx.multiplayerManager = null
            ctx.sceneRouter.navigate(MainMenuScene(ctx))
        }
        manager.onStartGame = { syncEpochMs ->
            matchSyncEpochMs = syncEpochMs
            finalSyncReceived = true
            log.info("[MultiplayerPlayScene] 최종 동기화 수신: syncEpochMs={} — READY 게이트 열림 조건 충족(로컬 보정={})",
                syncEpochMs, calibrationDone)
        }

        log.info("[MultiplayerPlayScene] enter: calibrationHlsUrl={}", calibrationHlsUrl.ifEmpty { "(없음/보정스킵)" })
        startCalibration()
    }

    override fun exit() {
        manager.onHostDisconnected = null
        manager.onStartGame = null
        inner.mediaStartOverride = null
        inner.readyPhaseGate = null
        inner.calibrationOverlayRenderer = null
        inner.exit()
        scheduler.shutdownNow()
        if (!exitToSpectator) manager.stop()
        super.exit()
    }

    /**
     * 클럭 핑퐁 + 테스트 스트림 실측 시작 지연 측정을 수행하고 완료되면 준비 완료를 알립니다.
     * 호스트는 자신에게 WebSocket 세션이 없으므로(핑퐁이 자연히 0개 샘플로 끝나 오프셋 0 유지)
     * markLocalReady()를, 클라이언트는 sendReady()를 호출합니다.
     */
    private fun startCalibration() {
        if (calibrationHlsUrl.isEmpty() || !ctx.videoBackground.isAvailable) {
            log.info("[MultiplayerPlayScene] 보정 스킵 (calibrationHlsUrl 비어있음={}, videoBackground.isAvailable={}) — 즉시 준비 완료 처리",
                calibrationHlsUrl.isEmpty(), ctx.videoBackground.isAvailable)
            calibrationDone = true
            if (manager.isHost) manager.markLocalReady() else manager.sendReady()
            return
        }
        val t0 = System.currentTimeMillis()
        Thread({
            syncCoordinator.beginPingRound(onDone = {
                log.debug("[MultiplayerPlayScene] 클럭 핑퐁 라운드 완료 ({}ms 경과), 테스트 스트림 재생 시작", System.currentTimeMillis() - t0)
                syncCoordinator.measureStreamStartupLatency(ctx.videoBackground, calibrationHlsUrl) { latencyMs ->
                    measuredStartupLatencyMs = latencyMs
                    calibrationDone = true
                    log.info("[MultiplayerPlayScene] 로컬 보정 완료 ({}ms 총 소요, 실측 시작지연={}ms) — Ready 전송", System.currentTimeMillis() - t0, latencyMs)
                    if (manager.isHost) manager.markLocalReady() else manager.sendReady()
                }
            })
        }, "stellane-calibration").apply { isDaemon = true; start() }
    }

    override fun update(deltaTime: Double) {
        // GameLoop이 MultiplayerPlayScene(Scene)에 주입한 InputSnapshot을
        // inner PlayScene에도 전달해야 laneEvents(D/F/J/K)가 처리됨
        inner.injectInput(lastInput)
        inner.update(deltaTime)

        // 곡이 정상 종료된 경우 관전 대기 화면으로 전환
        if (!finishSent && inner.phase == PlayScene.Phase.RESULT) {
            finishSent = true
            goSpectate()
            return
        }

        val se = inner.scoreEngine
        val score  = se.score
        val counts = se.counts

        // 점수/판정 변화 감지 → 네트워크 브로드캐스트
        if (score != prevScore || !counts.contentEquals(prevCounts)) {
            prevScore = score
            System.arraycopy(counts, 0, prevCounts, 0, 4)
            manager.broadcastScoreIfChanged(score, inner.scoreEngine.maxCombo, counts, totalNotes)
        }

        // 레인 홀드 상태 변화 감지
        if (!inner.laneHeld.contentEquals(prevLaneHeld)) {
            System.arraycopy(inner.laneHeld, 0, prevLaneHeld, 0, 4)
            manager.broadcastLaneHeld(inner.laneHeld)
        }
    }

    /** ESC 또는 곡 종료 시: FinishMsg 전송 후 SpectatorScene으로 전환해 다른 플레이어를 대기. */
    private fun goSpectate() {
        manager.sendFinish(totalNotes)
        manager.onGameOver = { entries ->
            ctx.sceneRouter.navigate(MultiplayerResultScene(ctx, entries, manager))
        }
        exitToSpectator = true
        ctx.sceneRouter.navigate(SpectatorScene(ctx, manager))
    }

    private inner class HudRenderSystem : RenderProducer {
        override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit
        override fun produce(world: World, out: MutableList<RenderCommand>) {
            // Gather inner PlayScene's render commands first, then add HUD on top
            inner.gatherRenderCommands().forEach { out.add(it) }
            out.add(RenderCommand.LegacyDrawContext { renderMultiplayerHud(this) })
        }
    }

    // ── 멀티플레이어 HUD 오버레이 ────────────────────────────────────────────────

    private fun renderMultiplayerHud(g: io.github.jwyoon1220.engine.DrawContext) {
        renderRankings(g)
        renderProgress(g)
    }

    private fun renderRankings(g: io.github.jwyoon1220.engine.DrawContext) {
        val rankings = manager.rankings
        val count = min(rankings.size, 10)
        if (count == 0) return

        val panelX = 8f
        val panelY = 8f
        val rowH   = 24f
        val panelW = 210f
        val panelH = rowH * count + 8f

        // 반투명 배경
        g.renderColor = RenderColor.of(0, 0, 0, 140)
        g.fillRoundRect(panelX, panelY, panelW, panelH, 8f)

        for (i in 0 until count) {
            val player = rankings[i]
            val rowY = panelY + 4f + i * rowH

            val isLocal = player.id == manager.localPlayerId
            val nameColor = if (isLocal) RenderColor.of(255, 220, 80) else RenderColor.of(200, 200, 220)
            val rankColor = if (isLocal) RenderColor.of(255, 180, 30) else RenderColor.of(140, 140, 180)

            // 순위 번호
            g.font = rankNumFont
            g.renderColor = rankColor
            g.drawString("${i + 1}.", panelX + 6f, rowY + rowH - 6f)

            // 이름
            g.font = rankFont
            g.renderColor = nameColor
            val displayName = if (player.name.length > 10) player.name.take(10) + "…" else player.name
            g.drawString(displayName, panelX + 30f, rowY + rowH - 6f)

            // 정확도 %
            g.font = barFont
            g.renderColor = RenderColor.of(140, 220, 140)
            val accStr = "%.1f%%".format(player.accuracy * 100f)
            g.drawStringRight(accStr, panelX + panelW - 6f, rowY + rowH - 6f)
        }
    }

    private fun renderProgress(g: io.github.jwyoon1220.engine.DrawContext) {
        val currentMs = inner.currentTimeMs.coerceAtLeast(0L)
        val curStr  = formatMs(currentMs)
        val totStr  = formatMs(totalMs)
        val display = "$curStr / $totStr"

        val progress = (currentMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)

        val barW = 200f
        val barH = 6f
        val barX = 1280f - barW - 8f
        val barY = 8f
        val textY = barY + barH + 18f

        // 진행바 배경
        g.renderColor = RenderColor.of(40, 40, 60, 160)
        g.fillRoundRect(barX, barY, barW, barH, 3f)

        // 진행바 채우기
        g.renderColor = RenderColor.of(120, 80, 220, 200)
        if (progress > 0f) g.fillRoundRect(barX, barY, barW * progress, barH, 3f)

        // 시간 텍스트
        g.font = timeFont
        g.renderColor = RenderColor.of(200, 200, 230)
        g.drawStringRight(display, barX + barW, textY)
    }

    private fun formatMs(ms: Long): String {
        val sec  = (ms / 1000L).coerceAtLeast(0L)
        val min  = sec / 60L
        val s    = sec % 60L
        return "%d:%02d".format(min, s)
    }

    // ── OpenGLRenderable / GlEffectProvider 위임 ─────────────────────────────────

    override val useOpenGLRenderer: Boolean get() = inner.useOpenGLRenderer
    override fun renderOpenGL(renderer: GlQuadBatchRenderer) = inner.renderOpenGL(renderer)
    override fun collectActiveGlEffects(): List<GlScreenEffectData> = inner.collectActiveGlEffects()

    // ── 입력 위임 ────────────────────────────────────────────────────────────────

    override fun keyPressed(key: Int, mods: Int) {
        when {
            // ESC: 기권 처리 → 관전자로 전환 (inner에게 전달하면 SongSelect로 가버림)
            key == io.github.jwyoon1220.engine.Keys.ESCAPE && !finishSent -> {
                finishSent = true
                goSpectate()
            }
            // 그 외 키는 inner에 위임 (단, ESC는 제외)
            key != io.github.jwyoon1220.engine.Keys.ESCAPE -> inner.keyPressed(key, mods)
        }
    }
    override fun keyReleased(key: Int, mods: Int) = inner.keyReleased(key, mods)
    override fun keyTyped   (codepoint: Int)      = inner.keyTyped(codepoint)
    override fun mousePressed (x: Float, y: Float, button: Int, mods: Int) = inner.mousePressed(x, y, button, mods)
    override fun mouseClicked (x: Float, y: Float, button: Int, mods: Int) = inner.mouseClicked(x, y, button, mods)
    override fun mouseReleased(x: Float, y: Float, button: Int, mods: Int) = inner.mouseReleased(x, y, button, mods)
    override fun mouseDragged (x: Float, y: Float, button: Int)            = inner.mouseDragged(x, y, button)
    override fun mouseScrolled(dy: Double) = inner.mouseScrolled(dy)

    override val rendersBackground: Boolean get() = inner.rendersBackground
}
