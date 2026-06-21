package io.github.jwyoon1220.app.multiplayer

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.ecs.PlayScene
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.engine.CustomGLRenderable
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.GlEffectProvider
import io.github.jwyoon1220.engine.GlQuadBatchRenderer
import io.github.jwyoon1220.engine.GlScreenEffectData
import io.github.jwyoon1220.engine.render.RenderColor
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * PlayScene을 위임(wrap)하여 멀티플레이어 HUD를 추가하는 씬.
 *
 * PlayScene 자체는 수정하지 않으며, 이 래퍼가:
 * - 왼쪽 위: 실시간 순위 (1위~10위)
 * - 오른쪽 위: 곡 진행도 (1:23 / 3:45)
 * - update() 후 점수 변화를 감지해 네트워크 스레드 큐에 넣음
 */
class MultiplayerPlayScene(
    ctx: GameContext,
    songEntry: SongEntry,
    chart: Chart,
    private val manager: MultiplayerManager
) : io.github.jwyoon1220.engine.ecs.Scene(), CustomGLRenderable, GlEffectProvider {

    private val inner = PlayScene(ctx, songEntry, chart)
    private val totalNotes = chart.notes.size
    private val totalMs: Long = chart.notes.maxOfOrNull { it.endTime ?: it.time } ?: 1L

    // HUD 폰트
    private val rankFont    = FontLoader.semiBold(16f)
    private val rankNumFont = FontLoader.bold(18f)
    private val timeFont    = FontLoader.semiBold(18f)
    private val barFont     = FontLoader.light(13f)

    // 이전 프레임 점수 (델타 체크)
    private var prevScore  = -1
    private var prevCounts = IntArray(4) { -1 }
    private var prevLaneHeld = BooleanArray(4)

    init {
        manager.setTotalNotes(totalNotes)
    }

    // ── GameState 위임 ───────────────────────────────────────────────────────────

    override fun enter() { super.enter(); inner.enter() }
    override fun exit()  { inner.exit(); manager.stop(); super.exit() }

    override fun update(deltaTime: Double) {
        // GameLoop이 MultiplayerPlayScene(Scene)에 주입한 InputSnapshot을
        // inner PlayScene에도 전달해야 laneEvents(D/F/J/K)가 처리됨
        inner.injectInput(lastInput)
        inner.update(deltaTime)

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

    override fun render(g: DrawContext) {
        inner.render(g)
        renderMultiplayerHud(g)
    }

    // ── 멀티플레이어 HUD 오버레이 ────────────────────────────────────────────────

    private fun renderMultiplayerHud(g: DrawContext) {
        renderRankings(g)
        renderProgress(g)
    }

    private fun renderRankings(g: DrawContext) {
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

    private fun renderProgress(g: DrawContext) {
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

    // ── CustomGLRenderable / GlEffectProvider 위임 ───────────────────────────────

    override val useCustomGlRenderer: Boolean get() = inner.useCustomGlRenderer
    override fun renderCustomGl(renderer: GlQuadBatchRenderer) = inner.renderCustomGl(renderer)
    override fun collectActiveGlEffects(): List<GlScreenEffectData> = inner.collectActiveGlEffects()

    // ── 입력 위임 ────────────────────────────────────────────────────────────────

    override fun keyPressed (key: Int, mods: Int) = inner.keyPressed(key, mods)
    override fun keyReleased(key: Int, mods: Int) = inner.keyReleased(key, mods)
    override fun keyTyped   (codepoint: Int)      = inner.keyTyped(codepoint)
    override fun mousePressed (x: Float, y: Float, button: Int, mods: Int) = inner.mousePressed(x, y, button, mods)
    override fun mouseClicked (x: Float, y: Float, button: Int, mods: Int) = inner.mouseClicked(x, y, button, mods)
    override fun mouseReleased(x: Float, y: Float, button: Int, mods: Int) = inner.mouseReleased(x, y, button, mods)
    override fun mouseDragged (x: Float, y: Float, button: Int)            = inner.mouseDragged(x, y, button)
    override fun mouseScrolled(dy: Double) = inner.mouseScrolled(dy)

    override val rendersBackground: Boolean get() = inner.rendersBackground
}
