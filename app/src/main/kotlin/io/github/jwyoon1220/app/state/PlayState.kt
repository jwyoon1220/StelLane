package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.DecorationRenderer
import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.AppSettings
import io.github.jwyoon1220.app.Const
import io.github.jwyoon1220.app.PlayRenderBackend
import io.github.jwyoon1220.core.song.DecorationParser
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.Note
import io.github.jwyoon1220.core.data.NoteType
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.judgment.Judgment
import io.github.jwyoon1220.core.judgment.JudgmentSystem
import io.github.jwyoon1220.core.scoring.ScoreEngine
import io.github.jwyoon1220.core.replay.ReplayFrame
import io.github.jwyoon1220.core.replay.ReplayFile
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.GameState
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.HitSound
import io.github.jwyoon1220.engine.LaneEventType
import io.github.jwyoon1220.engine.CustomGLRenderable
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.GlQuadBatchRenderer
import io.github.jwyoon1220.engine.Renderer
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.io.File
import java.io.FileWriter
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature

class PlayState(
    internal val ctx: GameContext,
    private val songEntry: SongEntry,
    internal val chart: Chart
) : GameState, CustomGLRenderable {

    companion object {
        private val COLOR_OVERLAY = Color(0, 0, 0, 130)
        private val COLOR_LANE_HELD = Color(70, 70, 130, 200)
        private val COLOR_LANE_NORMAL = Color(25, 25, 45, 200)
        private val COLOR_LANE_LINE = Color(70, 70, 100, 180)
        private val COLOR_BEAT_LINE = Color(70, 70, 100, 50)
        private val COLOR_JUDGE_LINE = Color(230, 230, 255)
        private val STROKE_JUDGE = BasicStroke(3f)

        private val COLOR_SHORT_FILL = Color(255, 210, 80)
        private val COLOR_SHORT_BORDER = Color(255, 245, 170)
        private val COLOR_LONG_BODY = Color(160, 80, 255, 150)
        private val COLOR_LONG_FILL = Color(190, 120, 255)
        private val COLOR_LONG_BORDER = Color(225, 185, 255)
        private val KEY_LABELS = arrayOf("D", "F", "J", "K")

        // 재사용 Color 상수 — renderCustomGl() 호출마다 객체 생성하지 않도록 미리 캐시
        private val COLOR_KEY_NORMAL   = Color(150, 150, 150)
        private val COLOR_COMBO_LABEL  = Color(180, 180, 180)
        private val COLOR_STAT_TEXT    = Color(160, 160, 160)
        private val COLOR_HINT_TEXT    = Color(100, 100, 110)

        // 판정 색상 배열 (Judgment 순서와 일치)
        private val JUDGMENT_COLORS = arrayOf(
            RenderColor.of(100, 220, 255),  // PERFECT
            RenderColor.of(255, 220, 80),   // GREAT
            RenderColor.of(100, 255, 130),  // GOOD
            RenderColor.of(255, 80, 80)     // MISS
        )
    }

    private var cachedScore = -1
    private var cachedScoreStr = "0000000"
    private val cachedCounts = IntArray(4) { -1 }
    private var cachedCountsStr = "P:0  G:0  g:0  M:0"
    private var cachedMaxCombo = -1
    private var cachedMaxComboStr = "MAX COMBO: 0"
    private var cachedCombo = -1
    private var cachedComboStr = ""
    
    // ── 성능 최적화: 매 프레임 시간 계산 캐싱 ───────────────────────────────
    private var lastCachedTimeMs = 0L
    private var lastCacheFrameId = -1L

    // ── 레이아웃 상수 ──────────────────────────────────────────────────────────
    private val LANE_COUNT      = Const.LANE_COUNT
    private val LANE_WIDTH      = Const.LANE_WIDTH
    private val TOTAL_WIDTH     = Const.TOTAL_WIDTH
    private val HIT_LINE_RATIO  = Const.HIT_LINE_RATIO
    private val SCROLL_SPEED    = Const.SCROLL_SPEED
    private val SPAWN_AHEAD_MS  = Const.SPAWN_AHEAD_MS
    private val JUDGE_FADE_MS   = Const.JUDGE_FADE_MS

    // ── 게임 페이즈 ────────────────────────────────────────────────────────────
    internal enum class Phase { READY, PLAYING, RESULT }
    @Volatile internal var phase = Phase.READY

    private val READY_DURATION_MS = Const.READY_DURATION_MS
    @Volatile internal var readyElapsedMs = 0.0
    private var coverImage: java.awt.image.BufferedImage? = null

    // FontMetrics 캐싱
    private var comboFontMetrics: io.github.jwyoon1220.engine.DrawFontMetrics? = null
    private var judgeFontMetrics: io.github.jwyoon1220.engine.DrawFontMetrics? = null
    private var scoreFontMetrics: io.github.jwyoon1220.engine.DrawFontMetrics? = null
    private var statFontMetrics: io.github.jwyoon1220.engine.DrawFontMetrics? = null
    private var hintFontMetrics: io.github.jwyoon1220.engine.DrawFontMetrics? = null
    private var readyLabelFontMetrics: io.github.jwyoon1220.engine.DrawFontMetrics? = null
    private var countdownFontMetrics: io.github.jwyoon1220.engine.DrawFontMetrics? = null

    // ── 게임 상태 ──────────────────────────────────────────────────────────────
    private lateinit var scoreEngine: ScoreEngine

    // DOD: Structure of Arrays — 객체 없음, 캐시 지역성 최대화
    // 화면에 동시에 존재하는 노트는 최대 수십 개이므로 256 슬롯으로 충분
    private val SOA_CAP   = 256
    internal val soaLane   = IntArray(SOA_CAP)      // 레인 (0–3)
    internal val soaTimeMs = LongArray(SOA_CAP)     // 헤드 타이밍 (ms)
    internal val soaEndMs  = LongArray(SOA_CAP)     // 테일 타이밍 (SHORT도 동일)
    internal val soaIsLong = BooleanArray(SOA_CAP)  // true = LONG
    internal val soaActive = BooleanArray(SOA_CAP)  // 판정 대기 중
    internal val soaHeld   = BooleanArray(SOA_CAP)  // LONG 홀드 중
    @Volatile internal var soaSize = 0              // 현재 활성 슬롯 수
    private val noteQueue   = ArrayDeque<Note>()
    internal val laneHeld    = BooleanArray(LANE_COUNT)

    // render()와 공유되는 값: @Volatile로 가시성 보장
    @Volatile private var combo           = 0
    @Volatile private var judgmentText    = ""
    @Volatile internal var judgmentColor   = RenderColor.WHITE
    @Volatile internal var judgmentFadeMs  = 0L

    // update()에서 계산된 보간 재생 시간을 render()가 읽음 (VLC 33ms 갱신 → nanoTime 보간)
    @Volatile internal var currentTimeMs: Long = 0L

    // render()에서 계산된 hitLineY를 update()가 참조하기 위해 저장
    @Volatile private var hitLineY = 612

    private var mediaStarted = false

    // ── 리플레이 기록 (골든 기준점) ──────────────────────────────────────────
    private val replayFrames = mutableListOf<ReplayFrame>()
    private var frameNum = 0
    @Volatile private var thisFrameLanePressed = mutableListOf<Int>()
    @Volatile private var thisFrameLaneReleased = mutableListOf<Int>()

    // ── 폰트 ──────────────────────────────────────────────────────────────────
    // ── 장식 렌더러 ──────────────────────────────────────────────────────────
    private var decorationRenderer: DecorationRenderer? = null

    // ── 장식 렌더러 ──────────────────────────────────────────────────────────
    // DrawContext (NanoVG) 는 GPU 가속이므로 CPU-side 캐시 불필요 — 매 프레임 직접 렌더링

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
    internal val notesLock = Any()
    override val useCustomGlRenderer: Boolean
        get() = true

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
    private fun judgColor(j: Judgment) = JUDGMENT_COLORS[j.ordinal]

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

        coverImage = songEntry.song.coverImagePath?.let { path ->
            runCatching { javax.imageio.ImageIO.read(File(songEntry.songDir, path)) }.getOrNull()
        }

        // 장식 데이터 로드 (decoration.json 없으면 null → 렌더링 건너뜀)
        decorationRenderer = DecorationParser.parseOrNull(songEntry.songDir)
            ?.let { DecorationRenderer(it, songEntry.songDir) }

        // 미디어는 READY → PLAYING 전환 시 재생
        ctx.videoBackground.onFinished = { phase = Phase.RESULT }
        ctx.inputManager.clearEvents()
    }

    override fun exit() {
        ctx.videoBackground.onFinished = null
        ctx.videoBackground.stop()
        ctx.videoBackground.setRate(1.0f)
        synchronized(notesLock) { soaSize = 0 }
        ctx.inputManager.clearEvents()
        
        // 리플레이 저장
        if (replayFrames.isNotEmpty()) saveReplay()
    }

    override fun update(deltaTime: Double) {
        if (phase == Phase.READY) {
            readyElapsedMs += deltaTime * 1000.0
            
            if (readyElapsedMs >= 1500.0) {
                for (event in ctx.inputManager.pollEvents()) {
                    laneHeld[event.lane] = event.type == LaneEventType.PRESS
                    if (event.type == LaneEventType.PRESS) {
                        HitSound.play()
                    }
                }
            } else {
                ctx.inputManager.clearEvents()
            }

            if (readyElapsedMs >= READY_DURATION_MS) {
                phase = Phase.PLAYING
                val mediaPath = resolveMediaPath()
                if (mediaPath != null) {
                    ctx.videoBackground.play(mediaPath)
                    val speedVal = io.github.jwyoon1220.app.AppSettings.playSpeed
                    val actualRate = if (speedVal <= 7.0f) {
                        0.5f + ((speedVal - 0.5f) / 6.5f) * 0.5f
                    } else {
                        1.0f + ((speedVal - 7.0f) / 28.0f) * 1.0f
                    }
                    ctx.videoBackground.setRate(actualRate)
                }
                mediaStarted = true
            }
            return
        }
        if (!mediaStarted) return
        
        // ── 성능: getSmoothTimeMs() 호출 최소화 (프레임 ID로 캐싱) ──────────────
        val frameId = ctx.videoBackground.getFrameId()
        if (frameId != lastCacheFrameId || lastCacheFrameId == -1L) {
            lastCachedTimeMs = ctx.videoBackground.getSmoothTimeMs() - chart.offsetMs
            lastCacheFrameId = frameId
        }
        currentTimeMs = lastCachedTimeMs
        val now = currentTimeMs

        // 입력 이벤트 처리 (handlePress/Release 내부에서 notesLock 사용)
        thisFrameLanePressed.clear()
        thisFrameLaneReleased.clear()
        for (event in ctx.inputManager.pollEvents()) {
            laneHeld[event.lane] = event.type == LaneEventType.PRESS
            when (event.type) {
                LaneEventType.PRESS   -> { thisFrameLanePressed.add(event.lane); HitSound.play(); handlePress(event.lane, now) }
                LaneEventType.RELEASE -> { thisFrameLaneReleased.add(event.lane); handleRelease(event.lane, now) }
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

    override fun render(g: DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height

        val hl      = (h * HIT_LINE_RATIO).toInt()
        hitLineY    = hl
        val lanesL  = (w - TOTAL_WIDTH) / 2
        
        val nowV    = if (phase == Phase.READY) readyElapsedMs - READY_DURATION_MS else currentTimeMs.toDouble()
        val nowD    = if (phase == Phase.READY) nowV.toDouble() else ctx.videoBackground.getSmoothTimeDouble() - chart.offsetMs

        val laneAlpha = if (phase == Phase.READY) {
            when {
                readyElapsedMs < 1500.0 -> 0.0f
                readyElapsedMs in 1500.0..2000.0 -> ((readyElapsedMs - 1500.0) / 500.0).toFloat()
                else -> 1.0f
            }
        } else 1.0f

        val infoAlpha = if (phase == Phase.READY) {
            when {
                readyElapsedMs < 1500.0 -> 1.0f
                readyElapsedMs in 1500.0..2000.0 -> (1.0 - (readyElapsedMs - 1500.0) / 500.0).toFloat()
                else -> 0.0f
            }
        } else 0.0f

        if (infoAlpha > 0f) {
            val oldComp = g.composite
            if (infoAlpha < 1.0f) {
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, infoAlpha)
            }

            g.color = Color(10, 5, 20)
            g.fillRect(0, 0, w, h)

            val cx = w / 2
            val coverS = 256
            val coverX = cx - coverS / 2
            val coverY = h / 2 - 180

            val cover = coverImage
            if (cover != null) {
                g.scoped {
                    setClip(coverX.toFloat(), coverY.toFloat(), coverS.toFloat(), coverS.toFloat())
                    drawImage(cover, coverX, coverY, coverS, coverS, null)
                }
                g.fillBoxGradientRect(
                    coverX.toFloat(), coverY.toFloat(), coverS.toFloat(), coverS.toFloat(),
                    10f, 20f,
                    Color(140, 80, 255, 100), Color(0, 0, 0, 0)
                )
            } else {
                g.fillLinearGradient(
                    coverX.toFloat(), coverY.toFloat(), coverS.toFloat(), coverS.toFloat(),
                    coverX.toFloat(), coverY.toFloat(), coverX.toFloat(), (coverY + coverS).toFloat(),
                    Color(38, 22, 68), Color(22, 12, 42)
                )
                g.color = Color(80, 55, 120)
                g.font  = FontLoader.bold(52f)
                g.drawStringCentered("♪", cx.toFloat(), coverY + coverS / 2f + 18f)
            }

            var ty = coverY + coverS + 35f

            g.font  = FontLoader.bold(32f)
            g.color = Color(235, 225, 255)
            g.drawStringCentered(songEntry.song.title, cx.toFloat(), ty); ty += 38f

            g.font  = FontLoader.regular(18f)
            g.color = Color(150, 125, 200)
            g.drawStringCentered(songEntry.song.artist, cx.toFloat(), ty); ty += 28f

            songEntry.song.bpm?.let {
                g.font  = FontLoader.light(15f)
                g.color = Color(110, 95, 140)
                g.drawStringCentered("BPM  $it", cx.toFloat(), ty)
            }

            if (infoAlpha < 1.0f) {
                g.composite = oldComp
            }
        }

        if (laneAlpha > 0f) {
            val oldComp = g.composite
            if (laneAlpha < 1.0f) {
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, laneAlpha)
            }

            g.color = COLOR_OVERLAY
            g.fillRect(0, 0, w, h)

            for (i in 0 until LANE_COUNT) {
                val lx = lanesL + i * LANE_WIDTH
                g.color = if (laneHeld[i]) COLOR_LANE_HELD else COLOR_LANE_NORMAL
                g.fillRect(lx, 0, LANE_WIDTH, h)
                g.color = COLOR_LANE_LINE
                g.drawLine(lx, 0, lx, h)
            }
            g.color = COLOR_LANE_LINE
            g.drawLine(lanesL + TOTAL_WIDTH, 0, lanesL + TOTAL_WIDTH, h)

            val bpm = songEntry.song.bpm ?: 120
            val beatMs = 60000.0 / bpm
            val startBeat = kotlin.math.floor(nowV / beatMs).toInt() - 1
            val endBeat = kotlin.math.ceil((nowV + SPAWN_AHEAD_MS) / beatMs).toInt() + 1
            
            for (b in startBeat..endBeat) {
                val lineTime = b * beatMs
                val y = hl - ((lineTime - nowD) * SCROLL_SPEED / 1000.0).toFloat()
                if (y in 0f..h.toFloat()) {
                    if (b % 4 == 0) {
                        g.color = COLOR_LANE_LINE
                        g.drawLine(lanesL, y.toInt(), lanesL + TOTAL_WIDTH, y.toInt())
                    } else {
                        g.color = COLOR_BEAT_LINE
                        g.drawLine(lanesL, y.toInt(), lanesL + TOTAL_WIDTH, y.toInt())
                    }
                }
            }

            val prevStroke = g.stroke
            g.stroke = STROKE_JUDGE
            g.color  = COLOR_JUDGE_LINE
            g.drawLine(lanesL, hl, lanesL + TOTAL_WIDTH, hl)
            g.stroke = prevStroke

            // 레인 키 라벨
            g.font = statFont
            val keyFm = statFontMetrics ?: g.getFontMetrics(statFont).also { statFontMetrics = it }
            for (i in 0 until LANE_COUNT) {
                val lx = lanesL + i * LANE_WIDTH
                g.color = if (laneHeld[i]) Color.WHITE else COLOR_KEY_NORMAL
                g.drawString(KEY_LABELS[i], lx + (LANE_WIDTH - keyFm.stringWidth(KEY_LABELS[i])) / 2, hl + 28)
            }

        // 장식 (depth < 0: 노트 이전)
        decorationRenderer?.render(g, currentTimeMs, beforeNotes = true)

        // 장식 (depth ≥0: 노트 위) + 화면 효과
        decorationRenderer?.render(g, currentTimeMs, beforeNotes = false)
        decorationRenderer?.renderScreenEffects(g, currentTimeMs)

        // 콤보
        if (combo > 0) {
            if (cachedCombo != combo) { cachedCombo = combo; cachedComboStr = combo.toString() }
            g.font  = comboFont
            g.color = Color.WHITE
            val cfm = comboFontMetrics ?: g.getFontMetrics(comboFont).also { comboFontMetrics = it }
            g.drawString(cachedComboStr, (w - cfm.stringWidth(cachedComboStr)) / 2, 96)

            g.font  = statFont
            g.color = COLOR_COMBO_LABEL
            val cl  = "COMBO"
            val sfm = statFontMetrics ?: g.getFontMetrics(statFont).also { statFontMetrics = it }
            g.drawString(cl, (w - sfm.stringWidth(cl)) / 2, 116)
        }

        // 판정 텍스트 (페이드아웃)
        if (judgmentFadeMs > 0 && judgmentText.isNotEmpty()) {
            val alpha = (judgmentFadeMs.toFloat() / JUDGE_FADE_MS).coerceIn(0f, 1f)
            val old   = g.composite
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            g.font  = judgeFont
            g.renderColor = judgmentColor
            val jfm = judgeFontMetrics ?: g.getFontMetrics(judgeFont).also { judgeFontMetrics = it }
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
        val sfm = scoreFontMetrics ?: g.getFontMetrics(scoreFont).also { scoreFontMetrics = it }
        g.drawString(cachedScoreStr, w - sfm.stringWidth(cachedScoreStr) - 20, 38)

        // 판정 카운트 (좌상단)
        g.font  = statFont
        g.color = COLOR_STAT_TEXT
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
        g.color = COLOR_HINT_TEXT
        g.drawString("ESC: Back", 10, h - 10)

        // READY 상태의 3초 카운트다운 렌더링
        if (phase == Phase.READY && readyElapsedMs >= 2000.0) {
            renderReadyOverlay(g, w, h)
        }

        // 결과 화면 오버레이
        if (phase == Phase.RESULT) renderResult(g, w, h)

        if (laneAlpha < 1.0f) {
            g.composite = oldComp
        }
    }
}

    private fun renderResult(g: DrawContext, w: Int, h: Int) {
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
        drawCenter(g, "RESULT", cx, y)
        y += 70

        // 점수
        g.font  = resultScore
        g.color = Color.WHITE
        drawCenter(g, "%07d".format(score), cx, y)
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
        drawCenter(g, rank, cx, y + 75)
        y += 140

        // 판정 카운트
        val line = "PERFECT ${counts[0]}   " +
                   "GREAT ${counts[1]}   " +
                   "GOOD ${counts[2]}   " +
                   "MISS ${counts[3]}"
        g.font  = resultStat
        g.color = Color(180, 180, 200)
        drawCenter(g, line, cx, y)
        y += 40

        // 최대 콤보
        g.font  = resultStat
        g.color = Color(150, 150, 170)
        drawCenter(g, "MAX COMBO  ${scoreEngine.maxCombo}", cx, y)
        y += 60

        // 힌트
        g.font  = resultHint
        g.color = Color(120, 120, 140)
        drawCenter(g, "Enter : 곡 선택으로", cx, y)
    }

    private fun renderReadyOverlay(g: DrawContext, w: Int, h: Int) {
        // "READY" label
        g.font  = judgeFont
        g.color = Color(180, 140, 240)
        val readyLabel = "READY"
        val rfm = judgeFontMetrics ?: g.getFontMetrics(judgeFont).also { judgeFontMetrics = it }
        g.drawString(readyLabel, (w - rfm.stringWidth(readyLabel)) / 2, h / 2 - 20)

        // Countdown number (3, 2, 1, GO!) with pulse
        val remaining = (READY_DURATION_MS - readyElapsedMs).coerceAtLeast(0.0)
        val (countStr, fraction) = when {
            remaining > 3000.0 -> Pair("3", (remaining - 3000.0) / 1000.0)
            remaining > 2000.0 -> Pair("2", (remaining - 2000.0) / 1000.0)
            remaining > 1000.0 -> Pair("1", (remaining - 1000.0) / 1000.0)
            else -> Pair("GO!", remaining / 1000.0)
        }
        val alpha = (fraction * 200 + 55).toInt().coerceIn(55, 255)

        g.font  = countdownFont
        g.color = if (countStr == "GO!") Color(100, 255, 130, alpha) else Color(255, 220, 80, alpha)
        val cfm = countdownFontMetrics ?: g.getFontMetrics(countdownFont).also { countdownFontMetrics = it }
        g.drawString(countStr, (w - cfm.stringWidth(countStr)) / 2, h / 2 + cfm.ascent - 30)
    }

    override fun renderCustomGl(renderer: GlQuadBatchRenderer) {
        if (!useCustomGlRenderer) return
        io.github.jwyoon1220.app.render.GameRenderer.getRenderer()?.render(renderer, this)
    }

    private fun drawCenter(g: DrawContext, text: String, cx: Int, y: Int) {
        val fm = g.fontMetrics
        g.drawString(text, cx - fm.stringWidth(text) / 2, y)
    }

    override fun keyPressed(key: Int, mods: Int) {
        if (key == Keys.ESCAPE) {
            ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.PLAY))
            return
        }
        if (phase == Phase.RESULT && key == Keys.ENTER) {
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

    private fun saveReplay() {
        try {
            val replay = ReplayFile(
                chartId = "${songEntry.song.title}_${chart.notes.size}",
                offsetMs = chart.offsetMs,
                totalNotes = chart.notes.size,
                bpm = (songEntry.song.bpm ?: 120).toFloat(),
                targetFps = 60,
                frames = replayFrames
            )
            
            val mapper = ObjectMapper()
            mapper.enable(SerializationFeature.INDENT_OUTPUT)
            val jsonStr = mapper.writeValueAsString(replay)
            
            val replayDir = File(songEntry.songDir, "replays").apply { mkdirs() }
            val outputFile = File(replayDir, "golden_replay.json")
            
            FileWriter(outputFile).use { it.write(jsonStr) }
            println("✓ Replay saved: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            System.err.println("✗ Failed to save replay: ${e.message}")
            e.printStackTrace()
        }
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
