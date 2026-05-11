package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.DecorationRenderer
import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.core.GameState
import io.github.jwyoon1220.core.song.DecorationParser
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.Note
import io.github.jwyoon1220.core.data.NoteType
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.judgment.Judgment
import io.github.jwyoon1220.core.judgment.JudgmentSystem
import io.github.jwyoon1220.core.scoring.ScoreEngine
import io.github.jwyoon1220.engine.HitSound
import io.github.jwyoon1220.engine.LaneEventType
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
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

    companion object {
        private val COLOR_OVERLAY = Color(0, 0, 0, 130)
        private val COLOR_LANE_HELD = Color(70, 70, 130, 200)
        private val COLOR_LANE_NORMAL = Color(25, 25, 45, 200)
        private val COLOR_LANE_LINE = Color(70, 70, 100, 180)
        private val COLOR_JUDGE_LINE = Color(230, 230, 255)
        private val STROKE_JUDGE = BasicStroke(3f)
        
        private val COLOR_SHORT_FILL = Color(255, 210, 80)
        private val COLOR_SHORT_BORDER = Color(255, 245, 170)
        private val COLOR_LONG_BODY = Color(160, 80, 255, 150)
        private val COLOR_LONG_FILL = Color(190, 120, 255)
        private val COLOR_LONG_BORDER = Color(225, 185, 255)
        private val KEY_LABELS = arrayOf("D", "F", "J", "K")
    }

    private var cachedScore = -1
    private var cachedScoreStr = "0000000"
    private val cachedCounts = IntArray(4) { -1 }
    private var cachedCountsStr = "P:0  G:0  g:0  M:0"
    private var cachedMaxCombo = -1
    private var cachedMaxComboStr = "MAX COMBO: 0"
    private var cachedCombo = -1
    private var cachedComboStr = ""

    // ── 레이아웃 상수 ──────────────────────────────────────────────────────────
    private val LANE_COUNT      = 4
    private val LANE_WIDTH      = 100
    private val TOTAL_WIDTH     = LANE_COUNT * LANE_WIDTH   // 400px
    private val HIT_LINE_RATIO  = 0.85f                     // 판정선 위치 (화면 높이 대비)
    private val SCROLL_SPEED    = 700f                       // px/s
    private val SPAWN_AHEAD_MS  = 1200L                      // 스폰 선행 시간(ms)
    private val JUDGE_FADE_MS   = 600L

    // ── 게임 페이즈 ────────────────────────────────────────────────────────────
    private enum class Phase { READY, PLAYING, RESULT }
    @Volatile private var phase = Phase.READY

    private val READY_DURATION_MS = 3_000.0
    @Volatile private var readyElapsedMs = 0.0

    // ── 게임 상태 ──────────────────────────────────────────────────────────────
    private lateinit var scoreEngine: ScoreEngine

    // DOD: Structure of Arrays — 객체 없음, 캐시 지역성 최대화
    // 화면에 동시에 존재하는 노트는 최대 수십 개이므로 256 슬롯으로 충분
    private val SOA_CAP   = 256
    private val soaLane   = IntArray(SOA_CAP)      // 레인 (0–3)
    private val soaTimeMs = LongArray(SOA_CAP)     // 헤드 타이밍 (ms)
    private val soaEndMs  = LongArray(SOA_CAP)     // 테일 타이밍 (SHORT도 동일)
    private val soaIsLong = BooleanArray(SOA_CAP)  // true = LONG
    private val soaActive = BooleanArray(SOA_CAP)  // 판정 대기 중
    private val soaHeld   = BooleanArray(SOA_CAP)  // LONG 홀드 중
    @Volatile private var soaSize = 0              // 현재 활성 슬롯 수
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
    // ── 장식 렌더러 ──────────────────────────────────────────────────────────
    private var decorationRenderer: DecorationRenderer? = null

    // ── 장식 레이어 캐시 ─────────────────────────────────────────────────────
    // 장식은 매 프레임 렌더링이 불필요 → decorTargetFps 속도로만 갱신
    /** 장식 레이어 렌더 FPS (기본 30). 변경하면 즉시 반영. */
    var decorTargetFps: Int = 30
    private var decorDirty = true
    private var decorLastRenderNs = 0L
    private var decorCacheBefore: BufferedImage? = null  // depth < 0 레이어
    private var decorCacheAfter:  BufferedImage? = null  // depth ≥ 0 + 화면 효과

    private val comboFont      = FontLoader.bold(56f)
    private val judgeFont      = FontLoader.bold(48f)
    private val scoreFont      = FontLoader.bold(28f)
    private val statFont       = FontLoader.regular(18f)
    private val hintFont       = FontLoader.light(15f)
    private val resultTitle    = FontLoader.bold(52f)
    private val resultScore    = FontLoader.bold(72f)
    private val resultStat     = FontLoader.semiBold(28f)
    private val resultHint     = FontLoader.light(20f)
    private val readyLabelFont = FontLoader.bold(48f)
    private val countdownFont  = FontLoader.bold(180f)

    // ── 렌더링 동기화 잠금 ────────────────────────────────────────────────────
    // SoA 배열은 GameLoopThread(update)와 EDT(render)에서 동시 접근 → lock
    private val notesLock = Any()

    // ── SoA 슬롯 제거 (swap-and-shrink, O(1)) ────────────────────────────────
    private fun soaRemoveAt(i: Int) {
        val last = --soaSize
        if (i < last) {
            soaLane[i]   = soaLane[last]
            soaTimeMs[i] = soaTimeMs[last]
            soaEndMs[i]  = soaEndMs[last]
            soaIsLong[i] = soaIsLong[last]
            soaActive[i] = soaActive[last]
            soaHeld[i]   = soaHeld[last]
        }
    }

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

        synchronized(notesLock) { soaSize = 0 }
        noteQueue.clear()
        chart.notes.sortedBy { it.time }.forEach { noteQueue.add(it) }
        laneHeld.fill(false)
        combo = 0; judgmentText = ""; judgmentFadeMs = 0L
        readyElapsedMs = 0.0
        mediaStarted = false
        phase = Phase.READY

        // 장식 데이터 로드 (decoration.json 없으면 null → 렌더링 건너뜀)
        decorationRenderer = DecorationParser.parseOrNull(songEntry.songDir)
            ?.let { DecorationRenderer(it, songEntry.songDir) }
        decorDirty = true
        decorCacheBefore = null; decorCacheAfter = null

        // 미디어는 READY → PLAYING 전환 시 재생
        ctx.videoBackground.onFinished = { phase = Phase.RESULT }
        ctx.inputManager.clearEvents()
    }

    override fun exit() {
        ctx.videoBackground.onFinished = null
        ctx.videoBackground.stop()
        synchronized(notesLock) { soaSize = 0 }
        ctx.inputManager.clearEvents()
    }

    override fun update(deltaTime: Double) {
        if (phase == Phase.READY) {
            readyElapsedMs += deltaTime * 1000.0
            if (readyElapsedMs >= READY_DURATION_MS) {
                phase = Phase.PLAYING
                val mediaPath = resolveMediaPath()
                if (mediaPath != null) ctx.videoBackground.play(mediaPath)
                mediaStarted = true
            }
            return
        }
        if (!mediaStarted) return
        // getSmoothTimeMs()로 VLC 33ms 갱신 간격을 nanoTime 보간으로 메워 부드러운 스크롤 구현
        currentTimeMs = ctx.videoBackground.getSmoothTimeMs() - chart.offsetMs
        val now = currentTimeMs

        // 입력 이벤트 처리 (handlePress/Release 내부에서 notesLock 사용)
        for (event in ctx.inputManager.pollEvents()) {
            laneHeld[event.lane] = event.type == LaneEventType.PRESS
            when (event.type) {
                LaneEventType.PRESS   -> { HitSound.play(); handlePress(event.lane, now) }
                LaneEventType.RELEASE -> handleRelease(event.lane, now)
            }
        }

        // SoA 스폰 + Miss/LONG 완료 처리 + 종료 체크 (단일 락)
        synchronized(notesLock) {
            // 스폰: noteQueue → SoA 슬롯
            while (noteQueue.isNotEmpty()) {
                val next = noteQueue.peek()!!
                if (next.time - now > SPAWN_AHEAD_MS) break
                noteQueue.poll()
                val slot = soaSize++
                soaLane[slot]   = next.lane
                soaTimeMs[slot] = next.time
                soaEndMs[slot]  = next.endTime ?: next.time
                soaIsLong[slot] = next.type == NoteType.LONG
                soaActive[slot] = true
                soaHeld[slot]   = false
            }

            // Miss / LONG 완료 처리 (swap-and-shrink: 제거 후 i 증분 없이 재검사)
            var i = 0
            while (i < soaSize) {
                if (!soaActive[i]) { soaRemoveAt(i); continue }

                if (soaHeld[i] && now >= soaEndMs[i]) {
                    applyJudgment(Judgment.PERFECT)
                    soaRemoveAt(i); continue
                }

                val tailMissed = soaIsLong[i] && !soaHeld[i] &&
                    now > soaEndMs[i] + JudgmentSystem.GOOD_MS
                if (!soaHeld[i] && (now - soaTimeMs[i] > JudgmentSystem.GOOD_MS || tailMissed)) {
                    applyJudgment(Judgment.MISS)
                    soaRemoveAt(i); continue
                }
                i++
            }

            if (phase == Phase.PLAYING && noteQueue.isEmpty() && soaSize == 0) {
                phase = Phase.RESULT
            }
        }

        judgmentFadeMs -= (deltaTime * 1000).toLong()
    }

    /**
     * 장식 레이어 캐시를 필요할 때만 갱신합니다.
     * decorDirty=true 이거나 decorTargetFps 주기가 경과하면 두 레이어(before/after)를 재렌더링.
     * 렌더링 비용이 있는 DecorationRenderer.render() 호출 횟수를 줄여 CPU 사용량 감소.
     */
    private fun tickDecorCache(timeMs: Long, w: Int, h: Int) {
        val renderer = decorationRenderer ?: return
        val now = System.nanoTime()
        val intervalNs = 1_000_000_000L / decorTargetFps.coerceAtLeast(1)
        if (!decorDirty && (now - decorLastRenderNs) < intervalNs) return

        fun layer(existing: BufferedImage?): BufferedImage =
            if (existing != null && existing.width == w && existing.height == h) existing
            else BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

        val before = layer(decorCacheBefore).also { decorCacheBefore = it }
        val after  = layer(decorCacheAfter) .also { decorCacheAfter  = it }

        // before 레이어 (depth < 0)
        before.createGraphics().also { cg ->
            val origCmp = cg.composite
            cg.composite = AlphaComposite.Clear
            cg.fillRect(0, 0, w, h)
            cg.composite = origCmp
            cg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            renderer.render(cg, timeMs, beforeNotes = true)
            cg.dispose()
        }
        // after 레이어 (depth ≥ 0 + 화면 효과)
        after.createGraphics().also { cg ->
            val origCmp = cg.composite
            cg.composite = AlphaComposite.Clear
            cg.fillRect(0, 0, w, h)
            cg.composite = origCmp
            cg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            renderer.render(cg, timeMs, beforeNotes = false)
            renderer.renderScreenEffects(cg, timeMs)
            cg.dispose()
        }

        decorDirty = false
        decorLastRenderNs = now
    }

    override fun render(g: Graphics2D) {
        val w = g.clipBounds?.width  ?: 1280
        val h = g.clipBounds?.height ?: 720

        if (phase == Phase.READY) { renderReady(g, w, h); return }

        val hl      = (h * HIT_LINE_RATIO).toInt()
        hitLineY    = hl
        val lanesL  = (w - TOTAL_WIDTH) / 2
        val now     = currentTimeMs   // update()에서 이미 보간된 값 재사용

        // 반투명 배경 오버레이
        g.color = COLOR_OVERLAY
        g.fillRect(0, 0, w, h)

        // 레인 배경
        for (i in 0 until LANE_COUNT) {
            val lx = lanesL + i * LANE_WIDTH
            g.color = if (laneHeld[i]) COLOR_LANE_HELD else COLOR_LANE_NORMAL
            g.fillRect(lx, 0, LANE_WIDTH, h)
            g.color = COLOR_LANE_LINE
            g.drawLine(lx, 0, lx, h)
        }
        g.color = COLOR_LANE_LINE
        g.drawLine(lanesL + TOTAL_WIDTH, 0, lanesL + TOTAL_WIDTH, h)

        // 판정선
        val prevStroke = g.stroke
        g.stroke = STROKE_JUDGE
        g.color  = COLOR_JUDGE_LINE
        g.drawLine(lanesL, hl, lanesL + TOTAL_WIDTH, hl)
        g.stroke = prevStroke

        // 레인 키 라벨
        g.font = statFont
        val keyFm = g.getFontMetrics(statFont)  // 루프 외부에서 1회만 획득
        for (i in 0 until LANE_COUNT) {
            val lx = lanesL + i * LANE_WIDTH
            g.color = if (laneHeld[i]) Color.WHITE else Color(150, 150, 150)
            g.drawString(KEY_LABELS[i], lx + (LANE_WIDTH - keyFm.stringWidth(KEY_LABELS[i])) / 2, hl + 28)
        }

        // 장식 (depth < 0: 노트 이전)
        tickDecorCache(currentTimeMs, w, h)
        decorCacheBefore?.let { g.drawImage(it, 0, 0, null) }

        // 노트 렌더링 (SoA — 원시 타입 배열 순회, 객체 참조 없음)
        synchronized(notesLock) {
            val count = soaSize
            for (i in 0 until count) {
                if (!soaActive[i] && !soaHeld[i]) continue
                val lx       = lanesL + soaLane[i] * LANE_WIDTH
                val noteTopY = hl - ((soaTimeMs[i] - now) * SCROLL_SPEED / 1000f).toInt()

                if (!soaIsLong[i]) {
                    // SHORT 노트: 금색
                    g.color = COLOR_SHORT_FILL
                    g.fillRoundRect(lx + 5, noteTopY - 18, LANE_WIDTH - 10, 18, 6, 6)
                    g.color = COLOR_SHORT_BORDER
                    g.drawRoundRect(lx + 5, noteTopY - 18, LANE_WIDTH - 10, 18, 6, 6)
                } else {
                    // LONG 노트: 보라색
                    val endTopY = hl - ((soaEndMs[i] - now) * SCROLL_SPEED / 1000f).toInt()
                    val bodyTop = min(noteTopY - 18, endTopY)
                    val bodyBtm = if (soaHeld[i]) hl else max(noteTopY, endTopY)
                    val bodyH   = bodyBtm - bodyTop
                    if (bodyH > 0) {
                        g.color = COLOR_LONG_BODY
                        g.fillRect(lx + 14, bodyTop, LANE_WIDTH - 28, bodyH)
                    }
                    // 헤드 / 테일
                    g.color = COLOR_LONG_FILL
                    g.fillRoundRect(lx + 5, noteTopY - 18, LANE_WIDTH - 10, 18, 6, 6)
                    g.color = COLOR_LONG_BORDER
                    g.drawRoundRect(lx + 5, noteTopY - 18, LANE_WIDTH - 10, 18, 6, 6)
                }
            }
        }

        // 장식 (depth ≥0: 노트 위) + 화면 효과 — 위에서 tickDecorCache 이미 호출됨
        decorCacheAfter?.let { g.drawImage(it, 0, 0, null) }

        // 콤보
        if (combo > 0) {
            if (cachedCombo != combo) { cachedCombo = combo; cachedComboStr = combo.toString() }
            g.font  = comboFont
            g.color = Color.WHITE
            val cfm = g.getFontMetrics(comboFont)
            g.drawString(cachedComboStr, (w - cfm.stringWidth(cachedComboStr)) / 2, 96)

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
        if (cachedScore != scoreEngine.score) {
            cachedScore = scoreEngine.score
            cachedScoreStr = "%07d".format(cachedScore)
        }
        val sfm = g.getFontMetrics(scoreFont)
        g.drawString(cachedScoreStr, w - sfm.stringWidth(cachedScoreStr) - 20, 38)

        // 판정 카운트 (좌상단)
        g.font  = statFont
        g.color = Color(160, 160, 160)
        val counts = scoreEngine.counts
        
        var countsDirty = false
        if (cachedCounts[0] != counts[0]) { cachedCounts[0] = counts[0]; countsDirty = true }
        if (cachedCounts[1] != counts[1]) { cachedCounts[1] = counts[1]; countsDirty = true }
        if (cachedCounts[2] != counts[2]) { cachedCounts[2] = counts[2]; countsDirty = true }
        if (cachedCounts[3] != counts[3]) { cachedCounts[3] = counts[3]; countsDirty = true }
        if (countsDirty) {
            cachedCountsStr = "P:${cachedCounts[0]}  G:${cachedCounts[1]}  g:${cachedCounts[2]}  M:${cachedCounts[3]}"
        }
        g.drawString(cachedCountsStr, lanesL, 20)

        // Max Combo (좌상단 두 번째 줄)
        if (cachedMaxCombo != scoreEngine.maxCombo) {
            cachedMaxCombo = scoreEngine.maxCombo
            cachedMaxComboStr = "MAX COMBO: $cachedMaxCombo"
        }
        g.drawString(cachedMaxComboStr, lanesL, 40)

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
        val line = "PERFECT ${counts[0]}   " +
                   "GREAT ${counts[1]}   " +
                   "GOOD ${counts[2]}   " +
                   "MISS ${counts[3]}"
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

    private fun renderReady(g: Graphics2D, w: Int, h: Int) {
        // Dark background
        g.color = Color(10, 5, 20)
        g.fillRect(0, 0, w, h)

        // Song title + artist
        g.font  = readyLabelFont
        g.color = Color(200, 160, 255)
        val titleStr = songEntry.song.title
        val tfm = g.getFontMetrics(readyLabelFont)
        g.drawString(titleStr, (w - tfm.stringWidth(titleStr)) / 2, h / 3)

        g.font  = statFont
        g.color = Color(140, 115, 175)
        val artistStr = songEntry.song.artist
        val afm = g.getFontMetrics(statFont)
        g.drawString(artistStr, (w - afm.stringWidth(artistStr)) / 2, h / 3 + 36)

        // "READY" label
        g.font  = judgeFont
        g.color = Color(180, 140, 240)
        val readyLabel = "READY"
        val rfm = g.getFontMetrics(judgeFont)
        g.drawString(readyLabel, (w - rfm.stringWidth(readyLabel)) / 2, h / 2 - 10)

        // Countdown number (3, 2, 1) with pulse
        val remaining = (READY_DURATION_MS - readyElapsedMs).coerceAtLeast(0.0)
        val countNum  = (remaining / 1000.0).toInt().coerceIn(0, 2) + 1
        val fraction  = (remaining % 1000.0) / 1000.0
        val alpha     = (fraction * 200 + 55).toInt().coerceIn(55, 255)

        g.font  = countdownFont
        g.color = Color(255, 220, 80, alpha)
        val countStr = countNum.toString()
        val cfm = g.getFontMetrics(countdownFont)
        g.drawString(countStr, (w - cfm.stringWidth(countStr)) / 2, h / 2 + cfm.ascent - 20)
    }

    private fun drawCenter(g: Graphics2D, font: java.awt.Font, text: String, cx: Int, y: Int) {
        val fm = g.getFontMetrics(font)
        g.drawString(text, cx - fm.stringWidth(text) / 2, y)
    }

    override fun keyPressed(e: KeyEvent) {
        if (e.keyCode == KeyEvent.VK_ESCAPE) {
            ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.PLAY))
            return
        }
        if (phase == Phase.RESULT && e.keyCode == KeyEvent.VK_ENTER) {
            ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.PLAY))
        }
    }

    // ── 내부 로직 ─────────────────────────────────────────────────────────────

    private fun handlePress(lane: Int, now: Long) {
        synchronized(notesLock) {
            var closestIdx  = -1
            var closestDiff = Long.MAX_VALUE
            for (i in 0 until soaSize) {
                if (soaLane[i] != lane || soaHeld[i] || !soaActive[i]) continue
                val diff = abs(now - soaTimeMs[i])
                if (diff < closestDiff) { closestDiff = diff; closestIdx = i }
            }
            if (closestIdx < 0 || closestDiff > JudgmentSystem.GOOD_MS) return@synchronized
            val judgment = JudgmentSystem.judge(now, soaTimeMs[closestIdx])
            if (!soaIsLong[closestIdx]) soaActive[closestIdx] = false
            else soaHeld[closestIdx] = true
            applyJudgment(judgment)
        }
    }

    private fun handleRelease(lane: Int, now: Long) {
        synchronized(notesLock) {
            for (i in 0 until soaSize) {
                if (soaLane[i] == lane && soaHeld[i]) {
                    val judgment = JudgmentSystem.judge(now, soaEndMs[i])
                    soaActive[i] = false
                    applyJudgment(judgment)
                    return@synchronized
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
