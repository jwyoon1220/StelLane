package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.core.GameState
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.Note
import io.github.jwyoon1220.core.data.NoteType
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.judgment.Judgment
import io.github.jwyoon1220.core.judgment.JudgmentSystem
import io.github.jwyoon1220.core.scoring.ScoreEngine
import io.github.jwyoon1220.engine.LaneEventType
import io.github.jwyoon1220.engine.pool.VisualNote
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.event.KeyEvent
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PlayState(
    private val ctx: GameContext,
    private val songEntry: SongEntry,
    private val chart: Chart
) : GameState {

    // ── 레이아웃 상수 ──────────────────────────────────────────────────────────
    private val LANE_COUNT      = 4
    private val LANE_WIDTH      = 100
    private val TOTAL_WIDTH     = LANE_COUNT * LANE_WIDTH   // 400px
    private val HIT_LINE_RATIO  = 0.85f                     // 판정선 위치 (화면 높이 대비)
    private val SCROLL_SPEED    = 700f                       // px/s
    private val SPAWN_AHEAD_MS  = 1200L                      // 스폰 선행 시간(ms)
    private val JUDGE_FADE_MS   = 600L

    // ── 게임 페이즈 ────────────────────────────────────────────────────────────
    private enum class Phase { PLAYING, RESULT }
    @Volatile private var phase = Phase.PLAYING

    // ── 게임 상태 ──────────────────────────────────────────────────────────────
    private lateinit var scoreEngine: ScoreEngine

    // fastutil ObjectArrayList — GameLoopThread 단일 접근
    private val activeNotes = ObjectArrayList<VisualNote>(64)
    private val noteQueue   = ArrayDeque<Note>()
    private val laneHeld    = BooleanArray(LANE_COUNT)

    // render()와 공유되는 값: @Volatile로 가시성 보장
    @Volatile private var combo           = 0
    @Volatile private var judgmentText    = ""
    @Volatile private var judgmentColor   = Color.WHITE
    @Volatile private var judgmentFadeMs  = 0L

    // update()에서 계산된 보간 재생 시간을 render()가 읽음 (VLC 33ms 갱신 → nanoTime 보간)
    @Volatile private var currentTimeMs: Long = 0L

    // render()에서 계산된 hitLineY를 update()가 참조하기 위해 저장
    @Volatile private var hitLineY = 612

    private var mediaStarted = false

    // ── 폰트 ──────────────────────────────────────────────────────────────────
    private val comboFont   = FontLoader.bold(56f)
    private val judgeFont   = FontLoader.bold(48f)
    private val scoreFont   = FontLoader.bold(28f)
    private val statFont    = FontLoader.regular(18f)
    private val hintFont    = FontLoader.light(15f)
    private val resultTitle = FontLoader.bold(52f)
    private val resultScore = FontLoader.bold(72f)
    private val resultStat  = FontLoader.semiBold(28f)
    private val resultHint  = FontLoader.light(20f)

    // ── 렌더링 동기화 잠금 ────────────────────────────────────────────────────
    // activeNotes는 GameLoopThread(update)와 EDT(render)에서 동시 접근 가능 → lock
    private val notesLock = Any()

    // ── 판정 색상 ─────────────────────────────────────────────────────────────
    private fun judgColor(j: Judgment) = when (j) {
        Judgment.PERFECT -> Color(100, 220, 255)
        Judgment.GREAT   -> Color(255, 220, 80)
        Judgment.GOOD    -> Color(100, 255, 130)
        Judgment.MISS    -> Color(255, 80, 80)
    }

    // ── GameState 구현 ────────────────────────────────────────────────────────

    override fun enter() {
        scoreEngine = ScoreEngine(chart.notes.size)

        synchronized(notesLock) { activeNotes.clear() }
        noteQueue.clear()
        chart.notes.sortedBy { it.time }.forEach { noteQueue.add(it) }
        laneHeld.fill(false)
        combo = 0; judgmentText = ""; judgmentFadeMs = 0L
        mediaStarted = false
        phase = Phase.PLAYING

        // 미디어 재생
        val mediaPath = resolveMediaPath()
        if (mediaPath != null) ctx.videoBackground.play(mediaPath)
        mediaStarted = true

        ctx.inputManager.clearEvents()
    }

    override fun exit() {
        ctx.videoBackground.stop()
        synchronized(notesLock) {
            ctx.notePool.releaseAll(activeNotes)
            activeNotes.clear()
        }
        ctx.inputManager.clearEvents()
    }

    override fun update(deltaTime: Double) {
        if (!mediaStarted) return
        // getSmoothTimeMs()로 VLC 33ms 갱신 간격을 nanoTime 보간으로 메워 부드러운 스크롤 구현
        currentTimeMs = ctx.videoBackground.getSmoothTimeMs() - chart.offsetMs
        val now = currentTimeMs

        // 스폰
        while (noteQueue.isNotEmpty()) {
            val next = noteQueue.peek()!!
            if (next.time - now > SPAWN_AHEAD_MS) break
            noteQueue.poll()
            val vn = ctx.notePool.acquire()
            vn.lane      = next.lane
            vn.timeMs    = next.time
            vn.endTimeMs = next.endTime ?: next.time
            vn.type      = next.type
            vn.active    = true
            vn.held      = false
            synchronized(notesLock) { activeNotes.add(vn) }
        }

        // 입력 이벤트 처리
        for (event in ctx.inputManager.pollEvents()) {
            laneHeld[event.lane] = event.type == LaneEventType.PRESS
            when (event.type) {
                LaneEventType.PRESS   -> handlePress(event.lane, now)
                LaneEventType.RELEASE -> handleRelease(event.lane, now)
            }
        }

        // 노트 자동 Miss / LONG 완료 처리
        synchronized(notesLock) {
            val iter = activeNotes.iterator()
            while (iter.hasNext()) {
                val vn = iter.next()
                if (!vn.active) { iter.remove(); ctx.notePool.release(vn); continue }

                // LONG 노트가 끝 시각까지 홀드됐으면 완료
                if (vn.held && now >= vn.endTimeMs) {
                    applyJudgment(Judgment.PERFECT)
                    iter.remove(); ctx.notePool.release(vn); continue
                }

                // SHORT 노트가 판정 창을 벗어남 → Miss
                if (!vn.held && now - vn.timeMs > JudgmentSystem.GOOD_MS) {
                    applyJudgment(Judgment.MISS)
                    iter.remove(); ctx.notePool.release(vn)
                }
            }
        }

        judgmentFadeMs -= (deltaTime * 1000).toLong()

        // 모든 노트가 처리되면 결과 화면으로
        if (phase == Phase.PLAYING && noteQueue.isEmpty()) {
            val empty = synchronized(notesLock) { activeNotes.isEmpty }
            if (empty) phase = Phase.RESULT
        }
    }

    override fun render(g: Graphics2D) {
        val w = g.clipBounds?.width  ?: 1280
        val h = g.clipBounds?.height ?: 720

        val hl      = (h * HIT_LINE_RATIO).toInt()
        hitLineY    = hl
        val lanesL  = (w - TOTAL_WIDTH) / 2
        val now     = currentTimeMs   // update()에서 이미 보간된 값 재사용

        // 반투명 배경 오버레이
        g.color = Color(0, 0, 0, 130)
        g.fillRect(0, 0, w, h)

        // 레인 배경
        for (i in 0 until LANE_COUNT) {
            val lx = lanesL + i * LANE_WIDTH
            g.color = if (laneHeld[i]) Color(70, 70, 130, 200) else Color(25, 25, 45, 200)
            g.fillRect(lx, 0, LANE_WIDTH, h)
            g.color = Color(70, 70, 100, 180)
            g.drawLine(lx, 0, lx, h)
        }
        g.color = Color(70, 70, 100, 180)
        g.drawLine(lanesL + TOTAL_WIDTH, 0, lanesL + TOTAL_WIDTH, h)

        // 판정선
        val prevStroke = g.stroke
        g.stroke = BasicStroke(3f)
        g.color  = Color(230, 230, 255)
        g.drawLine(lanesL, hl, lanesL + TOTAL_WIDTH, hl)
        g.stroke = prevStroke

        // 레인 키 라벨
        val keyLabels = arrayOf("D", "F", "J", "K")
        g.font = statFont
        for (i in 0 until LANE_COUNT) {
            val lx = lanesL + i * LANE_WIDTH
            val fm = g.getFontMetrics(statFont)
            g.color = if (laneHeld[i]) Color.WHITE else Color(150, 150, 150)
            g.drawString(keyLabels[i], lx + (LANE_WIDTH - fm.stringWidth(keyLabels[i])) / 2, hl + 28)
        }

        // 노트 렌더링
        synchronized(notesLock) {
            for (vn in activeNotes) {
                if (!vn.active && !vn.held) continue
                val lx      = lanesL + vn.lane * LANE_WIDTH
                val noteTopY = hl - ((vn.timeMs - now) * SCROLL_SPEED / 1000f).toInt()

                if (vn.type == NoteType.SHORT) {
                    g.color = Color(140, 200, 255)
                    g.fillRoundRect(lx + 5, noteTopY - 18, LANE_WIDTH - 10, 18, 6, 6)
                    g.color = Color(220, 240, 255)
                    g.drawRoundRect(lx + 5, noteTopY - 18, LANE_WIDTH - 10, 18, 6, 6)
                } else {
                    // LONG: 바디
                    val endTopY   = hl - ((vn.endTimeMs - now) * SCROLL_SPEED / 1000f).toInt()
                    val bodyTop   = min(noteTopY - 18, endTopY)
                    val bodyBtm   = if (vn.held) hl else max(noteTopY, endTopY)
                    val bodyH     = bodyBtm - bodyTop
                    if (bodyH > 0) {
                        g.color = Color(90, 150, 255, 160)
                        g.fillRect(lx + 14, bodyTop, LANE_WIDTH - 28, bodyH)
                    }
                    // 헤드
                    g.color = Color(140, 200, 255)
                    g.fillRoundRect(lx + 5, noteTopY - 18, LANE_WIDTH - 10, 18, 6, 6)
                    g.color = Color(220, 240, 255)
                    g.drawRoundRect(lx + 5, noteTopY - 18, LANE_WIDTH - 10, 18, 6, 6)
                }
            }
        }

        // 콤보
        if (combo > 0) {
            g.font  = comboFont
            g.color = Color.WHITE
            val cs  = combo.toString()
            val cfm = g.getFontMetrics(comboFont)
            g.drawString(cs, (w - cfm.stringWidth(cs)) / 2, 96)

            g.font  = statFont
            g.color = Color(180, 180, 180)
            val cl  = "COMBO"
            val sfm = g.getFontMetrics(statFont)
            g.drawString(cl, (w - sfm.stringWidth(cl)) / 2, 116)
        }

        // 판정 텍스트 (페이드아웃)
        if (judgmentFadeMs > 0 && judgmentText.isNotEmpty()) {
            val alpha = (judgmentFadeMs.toFloat() / JUDGE_FADE_MS).coerceIn(0f, 1f)
            val old   = g.composite
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            g.font  = judgeFont
            g.color = judgmentColor
            val jfm = g.getFontMetrics(judgeFont)
            g.drawString(judgmentText, (w - jfm.stringWidth(judgmentText)) / 2, hl - 70)
            g.composite = old
        }

        // 점수 (우상단)
        g.font  = scoreFont
        g.color = Color.WHITE
        val sc  = "%07d".format(scoreEngine.score)
        val sfm = g.getFontMetrics(scoreFont)
        g.drawString(sc, w - sfm.stringWidth(sc) - 20, 38)

        // 판정 카운트 (좌상단)
        g.font  = statFont
        g.color = Color(160, 160, 160)
        val counts = scoreEngine.counts
        g.drawString("P:${counts[Judgment.PERFECT]}  G:${counts[Judgment.GREAT]}  " +
                "g:${counts[Judgment.GOOD]}  M:${counts[Judgment.MISS]}", lanesL, 20)

        // Max Combo (좌상단 두 번째 줄)
        g.drawString("MAX COMBO: ${scoreEngine.maxCombo}", lanesL, 40)

        // ESC 힌트
        g.font  = hintFont
        g.color = Color(100, 100, 110)
        g.drawString("ESC: Back", 10, h - 10)

        // 결과 화면 오버레이
        if (phase == Phase.RESULT) renderResult(g, w, h)
    }

    private fun renderResult(g: Graphics2D, w: Int, h: Int) {
        // 반투명 어두운 배경
        g.color = Color(0, 0, 0, 200)
        g.fillRect(0, 0, w, h)

        val counts = scoreEngine.counts
        val score  = scoreEngine.score
        val rank   = when {
            score >= 980_000 -> "SS"
            score >= 950_000 -> "S"
            score >= 900_000 -> "A"
            score >= 800_000 -> "B"
            score >= 600_000 -> "C"
            else             -> "D"
        }

        val cx = w / 2
        var y  = h / 2 - 180

        // RESULT 타이틀
        g.font  = resultTitle
        g.color = Color(200, 200, 220)
        drawCenter(g, resultTitle, "RESULT", cx, y)
        y += 70

        // 점수
        g.font  = resultScore
        g.color = Color.WHITE
        drawCenter(g, resultScore, "%07d".format(score), cx, y)
        y += 20

        // 랭크
        val rankColor = when (rank) {
            "SS" -> Color(255, 220, 80)
            "S"  -> Color(200, 240, 255)
            "A"  -> Color(130, 220, 130)
            "B"  -> Color(130, 180, 255)
            "C"  -> Color(200, 200, 200)
            else -> Color(160, 100, 100)
        }
        g.font  = resultScore
        g.color = rankColor
        drawCenter(g, resultScore, rank, cx, y + 75)
        y += 140

        // 판정 카운트
        val line = "PERFECT ${counts[Judgment.PERFECT]}   " +
                   "GREAT ${counts[Judgment.GREAT]}   " +
                   "GOOD ${counts[Judgment.GOOD]}   " +
                   "MISS ${counts[Judgment.MISS]}"
        g.font  = resultStat
        g.color = Color(180, 180, 200)
        drawCenter(g, resultStat, line, cx, y)
        y += 40

        // 최대 콤보
        g.font  = resultStat
        g.color = Color(150, 150, 170)
        drawCenter(g, resultStat, "MAX COMBO  ${scoreEngine.maxCombo}", cx, y)
        y += 60

        // 힌트
        g.font  = resultHint
        g.color = Color(120, 120, 140)
        drawCenter(g, resultHint, "Enter : 곡 선택으로", cx, y)
    }

    private fun drawCenter(g: Graphics2D, font: java.awt.Font, text: String, cx: Int, y: Int) {
        val fm = g.getFontMetrics(font)
        g.drawString(text, cx - fm.stringWidth(text) / 2, y)
    }

    override fun keyPressed(e: KeyEvent) {
        if (phase == Phase.RESULT && e.keyCode == KeyEvent.VK_ENTER) {
            ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.PLAY))
            return
        }
        if (e.keyCode == KeyEvent.VK_ESCAPE) {
            ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.PLAY))
        }
    }

    // ── 내부 로직 ─────────────────────────────────────────────────────────────

    private fun handlePress(lane: Int, now: Long) {
        var closest: VisualNote? = null
        var closestDiff = Long.MAX_VALUE

        synchronized(notesLock) {
            for (vn in activeNotes) {
                if (vn.lane != lane || vn.held || !vn.active) continue
                val diff = abs(now - vn.timeMs)
                if (diff < closestDiff) { closestDiff = diff; closest = vn }
            }
        }

        val vn = closest ?: return
        if (closestDiff > JudgmentSystem.GOOD_MS) return

        val judgment = JudgmentSystem.judge(now, vn.timeMs)
        if (vn.type == NoteType.SHORT) {
            vn.active = false
        } else {
            vn.held = true
        }
        applyJudgment(judgment)
    }

    private fun handleRelease(lane: Int, now: Long) {
        synchronized(notesLock) {
            for (vn in activeNotes) {
                if (vn.lane == lane && vn.held) {
                    val judgment = JudgmentSystem.judge(now, vn.endTimeMs)
                    vn.active = false
                    applyJudgment(judgment)
                    return
                }
            }
        }
    }

    private fun applyJudgment(j: Judgment) {
        scoreEngine.onJudgment(j)
        combo = if (j != Judgment.MISS) combo + 1 else 0
        judgmentText   = j.name
        judgmentColor  = judgColor(j)
        judgmentFadeMs = JUDGE_FADE_MS
    }

    private fun resolveMediaPath(): String? {
        val song = songEntry.song
        return when {
            song.videoPath != null -> File(songEntry.songDir, song.videoPath).absolutePath
            song.audioPath != null -> File(songEntry.songDir, song.audioPath).absolutePath
            else                   -> null
        }
    }
}
