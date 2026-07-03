package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.engine.DecorationRenderer
import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.Const
import io.github.jwyoon1220.app.resolveMediaPath
import io.github.jwyoon1220.core.song.DecorationParser
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.Note
import io.github.jwyoon1220.core.data.NoteType
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.judgment.Judgment
import io.github.jwyoon1220.core.judgment.JudgmentSystem
import io.github.jwyoon1220.core.scoring.ScoreEngine
import io.github.jwyoon1220.core.replay.ReplayFile
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderCommand
import io.github.jwyoon1220.engine.HitSound
import io.github.jwyoon1220.engine.OpenGLRenderable
import io.github.jwyoon1220.engine.GlEffectProvider
import io.github.jwyoon1220.engine.GlScreenEffectData
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.GlQuadBatchRenderer
import io.github.jwyoon1220.engine.ecs.Scene
import java.awt.BasicStroke
import java.io.File
import java.io.FileWriter
import java.util.ArrayDeque
import kotlin.math.abs
import io.github.jwyoon1220.core.replay.ReplayFrame
import io.github.jwyoon1220.engine.DrawFontMetrics
import it.unimi.dsi.fastutil.objects.ObjectArrayList

/**
 * ECS 기반 게임플레이 씬.
 *
 * PlayState의 검증된 시뮬레이션/렌더 로직(SoA 배열, 락, 커스텀 GL, 비디오 동기화, 리플레이)을
 * 그대로 유지하면서 Scene 백본 위에서 동작합니다. 타이밍/판정이 매우 민감하므로 권위 있는
 * 시뮬레이션은 기존 코드 경로를 보존하고, ECS 구조(Scene/World)로만 이식했습니다.
 *
 * 커스텀 GL 렌더러([io.github.jwyoon1220.app.render.Renderer])가 이 씬의 SoA 배열을 직접
 * 읽으므로 멤버 가시성(internal)을 유지합니다.
 */
class PlayScene(
    internal val ctx: GameContext,
    private val songEntry: SongEntry,
    internal val chart: Chart
) : Scene(), OpenGLRenderable, GlEffectProvider {

    companion object {
        private val COLOR_OVERLAY = RenderColor.of(0, 0, 0, 130)
        private val COLOR_LANE_HELD = RenderColor.of(70, 70, 130, 200)
        private val COLOR_LANE_NORMAL = RenderColor.of(25, 25, 45, 200)
        private val COLOR_LANE_LINE = RenderColor.of(70, 70, 100, 180)
        private val COLOR_BEAT_LINE = RenderColor.of(70, 70, 100, 50)
        private val COLOR_JUDGE_LINE = RenderColor.of(230, 230, 255)
        private val STROKE_JUDGE = BasicStroke(3f)

        private val COLOR_SHORT_FILL = RenderColor.of(255, 210, 80)
        private val COLOR_SHORT_BORDER = RenderColor.of(255, 245, 170)
        private val COLOR_LONG_BODY = RenderColor.of(160, 80, 255, 150)
        private val COLOR_LONG_FILL = RenderColor.of(190, 120, 255)
        private val COLOR_LONG_BORDER = RenderColor.of(225, 185, 255)
        private val KEY_LABELS = arrayOf("D", "F", "J", "K")

        // 재사용 RenderColor 상수 — renderOpenGL() 호출마다 객체 생성하지 않도록 미리 캐시
        private val COLOR_KEY_NORMAL   = RenderColor.of(150, 150, 150)
        private val COLOR_COMBO_LABEL  = RenderColor.of(180, 180, 180)
        private val COLOR_STAT_TEXT    = RenderColor.of(160, 160, 160)
        private val COLOR_HINT_TEXT    = RenderColor.of(100, 100, 110)

        // 판정 색상 배열 (Judgment 순서와 일치)
        private val JUDGMENT_COLORS = arrayOf(
            RenderColor.of(100, 220, 255),  // PERFECT
            RenderColor.of(255, 220, 80),   // GREAT
            RenderColor.of(100, 255, 130),  // GOOD
            RenderColor.of(255, 80, 80)     // MISS
        )

        // 결과 화면 색상 캐시
        private val COLOR_RESULT_OVERLAY  = RenderColor.of(0, 0, 0, 210)
        private val COLOR_RESULT_TITLE    = RenderColor.of(180, 160, 220)
        private val COLOR_RESULT_SCORE    = RenderColor.WHITE
        private val COLOR_RESULT_STAT     = RenderColor.of(180, 170, 210)
        private val COLOR_RESULT_STAT2    = RenderColor.of(140, 130, 170)
        private val COLOR_RESULT_HINT     = RenderColor.of(110, 100, 140)
        private val COLOR_RESULT_ACCURACY = RenderColor.of(160, 210, 255)
        private val COLOR_RANK_SS  = RenderColor.of(255, 220, 80)
        private val COLOR_RANK_S   = RenderColor.of(200, 240, 255)
        private val COLOR_RANK_A   = RenderColor.of(130, 220, 130)
        private val COLOR_RANK_B   = RenderColor.of(130, 180, 255)
        private val COLOR_RANK_C   = RenderColor.of(200, 200, 200)
        private val COLOR_RANK_D   = RenderColor.of(160, 100, 100)
        private val COLOR_READY_LABEL = RenderColor.of(180, 140, 240)
        private val COLOR_COUNTDOWN_GO = RenderColor.of(100, 255, 130)

        private val REPLAY_MAPPER = com.fasterxml.jackson.databind.ObjectMapper().apply {
            enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
        }

        // playSpeed 변환 상수
        private const val SPEED_PIVOT     = 7.0f
        private const val SPEED_MIN_RATE  = 0.5f
        private const val SPEED_MIN_VAL   = 0.5f
        private const val SPEED_LOW_RANGE = 6.5f   // SPEED_PIVOT - SPEED_MIN_VAL
        private const val SPEED_HIGH_MAX  = 35.0f
        private const val SPEED_HIGH_RANGE= 28.0f  // SPEED_HIGH_MAX - SPEED_PIVOT
    }

    private var cachedScore = -1
    private var cachedScoreStr = "0000000"
    private val cachedCounts = IntArray(4) { -1 }
    private var cachedCountsStr = "P:0  G:0  g:0  M:0"
    private var cachedMaxCombo = -1
    private var cachedMaxComboStr = "MAX COMBO: 0"
    private var cachedCombo = -1
    private var cachedComboStr = ""

    // update()에서 매 프레임 재사용 — mutableListOf() 반복 할당 방지
    private val pressedLanesBuf  = ArrayList<Int>(4)
    private val releasedLanesBuf = ArrayList<Int>(4)

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
    @Volatile private var coverImage: java.awt.image.BufferedImage? = null

    // FontMetrics 캐싱
    private var comboFontMetrics: DrawFontMetrics? = null
    private var judgeFontMetrics: DrawFontMetrics? = null
    private var scoreFontMetrics: DrawFontMetrics? = null
    private var statFontMetrics: DrawFontMetrics? = null
    private var hintFontMetrics: DrawFontMetrics? = null
    private var readyLabelFontMetrics: DrawFontMetrics? = null
    private var countdownFontMetrics: DrawFontMetrics? = null

    // ── 게임 상태 ──────────────────────────────────────────────────────────────
    internal lateinit var scoreEngine: ScoreEngine

    // DOD: Structure of Arrays — 객체 없음, 캐시 지역성 최대화
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
    @Volatile internal var currentTimeDouble: Double = 0.0

    // 설계 해상도(720p) 기준 고정 hitLineY — render/update 양쪽에서 동일하게 사용
    internal val hitLineY: Int = (720 * HIT_LINE_RATIO).toInt()

    private var mediaStarted = false

    // ── 리플레이 기록 (골든 기준점) ──────────────────────────────────────────
    private val replayFrames = ObjectArrayList<ReplayFrame>()
    private var replayFrameNum = 0
    private var replayLastPressedLanes: List<Int> = emptyList()
    private var replayLastReleasedLanes: List<Int> = emptyList()
    private var replayLastJudgment: String = ""

    // ── 장식 렌더러 ──────────────────────────────────────────────────────────
    @Volatile private var decorationRenderer: DecorationRenderer? = null

    private val comboFont      = FontLoader.interBold(60f)
    private val judgeFont      = FontLoader.interBold(46f)
    private val scoreFont      = FontLoader.interSemiBold(28f)
    private val statFont       = FontLoader.interRegular(16f)
    private val hintFont       = FontLoader.interLight(14f)
    private val resultTitle    = FontLoader.interSemiBold(22f)
    private val resultScore    = FontLoader.interBold(76f)
    private val resultStat     = FontLoader.interRegular(22f)
    private val resultHint     = FontLoader.interLight(18f)
    private val readyLabelFont = FontLoader.interBold(48f)
    private val countdownFont  = FontLoader.interBold(200f)
    private val loadingTitleFont  = FontLoader.bold(32f)       // 한국어 제목은 MaruBuri 유지
    private val loadingArtistFont = FontLoader.regular(18f)    // 한국어 아티스트는 MaruBuri 유지
    private val loadingBpmFont    = FontLoader.interLight(15f)

    // ── 렌더링 동기화 잠금 ────────────────────────────────────────────────────
    // SoA 배열은 GameLoopThread(update)와 EDT(render)에서 동시 접근 → lock
    internal val notesLock = Any()
    override val useOpenGLRenderer: Boolean get() = true

    /** NoteRenderer가 읽는 페이드인 알파 — render()와 renderOpenGL() 양쪽에서 공유. */
    internal val laneAlpha: Float
        get() = if (phase == Phase.READY) when {
            readyElapsedMs < 1500.0 -> 0.0f
            readyElapsedMs in 1500.0..2000.0 -> ((readyElapsedMs - 1500.0) / 500.0).toFloat()
            else -> 1.0f
        } else 1.0f

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

    // ── Scene / GameState 구현 ──────────────────────────────────────────────────

    override fun enter() {
        super.enter()
        scoreEngine = ScoreEngine(chart.notes.size)

        synchronized(notesLock) { soaSize = 0 }
        noteQueue.clear()
        chart.notes.sortedBy { it.time }.forEach { noteQueue.add(it) }
        laneHeld.fill(false)
        combo = 0; judgmentText = ""; judgmentFadeMs = 0L
        readyElapsedMs = 0.0
        mediaStarted = false
        phase = Phase.READY
        replayFrames.clear(); replayFrameNum = 0

        coverImage = null
        val coverPath = songEntry.song.coverImagePath
        if (coverPath != null) {
            java.util.concurrent.CompletableFuture.supplyAsync {
                runCatching { javax.imageio.ImageIO.read(File(songEntry.songDir, coverPath)) }.getOrNull()
            }.thenAccept { img ->
                coverImage = img
            }
        }

        decorationRenderer = null
        java.util.concurrent.CompletableFuture.supplyAsync {
            DecorationParser.parseOrNull(songEntry.songDir)
                ?.let { DecorationRenderer(it, songEntry.songDir) }
        }.thenAccept { renderer ->
            decorationRenderer = renderer
        }

        // 미디어는 READY → PLAYING 전환 시 재생
        ctx.videoBackground.onFinished = { phase = Phase.RESULT }
        ctx.inputManager.clearEvents()
        register(PlayRenderSystem())
    }

    override fun exit() {
        ctx.videoBackground.onFinished = null
        ctx.videoBackground.stop()
        ctx.videoBackground.setRate(1.0f)
        synchronized(notesLock) { soaSize = 0 }
        ctx.inputManager.clearEvents()

        // 리플레이 저장
        if (replayFrames.isNotEmpty()) saveReplay()
        super.exit()
    }

    override fun update(deltaTime: Double) {
        if (phase == Phase.READY) {
            readyElapsedMs += deltaTime * 1000.0

            if (readyElapsedMs >= 1500.0) {
                for (event in lastInput.laneEvents) {
                    laneHeld[event.lane] = event.pressed
                    if (event.pressed) HitSound.play()
                }
            }

            if (readyElapsedMs >= READY_DURATION_MS) {
                phase = Phase.PLAYING
                val mediaPath = resolveMediaPath()
                if (mediaPath != null) {
                    ctx.videoBackground.play(mediaPath)
                    val speedVal = io.github.jwyoon1220.app.AppSettings.playSpeed
                    val actualRate = if (speedVal <= SPEED_PIVOT) {
                        SPEED_MIN_RATE + ((speedVal - SPEED_MIN_VAL) / SPEED_LOW_RANGE) * SPEED_MIN_RATE
                    } else {
                        1.0f + ((speedVal - SPEED_PIVOT) / SPEED_HIGH_RANGE)
                    }
                    ctx.videoBackground.setRate(actualRate)
                }
                mediaStarted = true
            }
            return
        }
        if (!mediaStarted) return

        val t = ctx.videoBackground.getSmoothTimeDouble() - chart.offsetMs
        currentTimeDouble = t
        currentTimeMs = t.toLong()
        val now = currentTimeMs

        // audioFade 효과 적용
        decorationRenderer?.computeTargetVolumePercent(now)?.let {
            ctx.videoBackground.setTargetVolumePercent(it)
        }

        // 입력 이벤트 처리 (handlePress/Release 내부에서 notesLock 사용)
        pressedLanesBuf.clear()
        releasedLanesBuf.clear()
        for (event in lastInput.laneEvents) {
            laneHeld[event.lane] = event.pressed
            if (event.pressed) { pressedLanesBuf.add(event.lane); HitSound.play(); handlePress(event.lane, now) }
            else               { releasedLanesBuf.add(event.lane); handleRelease(event.lane, now) }
        }
        // 키 입력이 있는 프레임만 리플레이에 기록하므로 non-empty시에만 toList() 복사
        replayLastPressedLanes  = if (pressedLanesBuf.isEmpty())  emptyList() else pressedLanesBuf.toList()
        replayLastReleasedLanes = if (releasedLanesBuf.isEmpty()) emptyList() else releasedLanesBuf.toList()

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

        // 리플레이 프레임 기록 (입력 또는 판정이 있는 프레임만)
        if (replayLastPressedLanes.isNotEmpty() || replayLastReleasedLanes.isNotEmpty() || replayLastJudgment.isNotEmpty()) {
            replayFrames.add(ReplayFrame(
                frameNum           = replayFrameNum,
                gameTimeMs         = now,
                lanePressed        = replayLastPressedLanes,
                laneReleased       = replayLastReleasedLanes,
                score              = scoreEngine.score,
                combo              = combo,
                maxCombo           = scoreEngine.maxCombo,
                judgmentCounts     = scoreEngine.counts.toList(),
                judgmentThisFrame  = replayLastJudgment,
                activeNoteIndices  = synchronized(notesLock) { (0 until soaSize).filter { soaActive[it] } }
            ))
            replayLastJudgment = ""
        }
        replayFrameNum++
    }

    private inner class PlayRenderSystem : RenderProducer {
        override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit
        override fun produce(world: World, out: MutableList<RenderCommand>) {
            out.add(RenderCommand.LegacyDrawContext { renderContents(this) })
        }
    }

    private fun renderContents(g: io.github.jwyoon1220.engine.DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height

        val hl     = hitLineY
        val lanesL = (w - TOTAL_WIDTH) / 2

        val nowV    = if (phase == Phase.READY) readyElapsedMs - READY_DURATION_MS else currentTimeMs.toDouble()
        val nowD    = if (phase == Phase.READY) nowV else currentTimeDouble

        val infoAlpha = if (phase == Phase.READY) {
            when {
                readyElapsedMs < 1500.0 -> 1.0f
                readyElapsedMs in 1500.0..2000.0 -> (1.0 - (readyElapsedMs - 1500.0) / 500.0).toFloat()
                else -> 0.0f
            }
        } else 0.0f

        if (infoAlpha > 0f) {
            val oldComp = g.globalAlpha
            if (infoAlpha < 1.0f) {
                g.globalAlpha = infoAlpha
            }

            g.renderColor = RenderColor.of(10, 5, 20)
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
                    RenderColor.of(140, 80, 255, 100), RenderColor.of(0, 0, 0, 0)
                )
            } else {
                g.fillLinearGradient(
                    coverX.toFloat(), coverY.toFloat(), coverS.toFloat(), coverS.toFloat(),
                    coverX.toFloat(), coverY.toFloat(), coverX.toFloat(), (coverY + coverS).toFloat(),
                    RenderColor.of(38, 22, 68), RenderColor.of(22, 12, 42)
                )
                g.renderColor = RenderColor.of(80, 55, 120)
                g.font  = FontLoader.bold(52f)
                g.drawStringCentered("♪", cx.toFloat(), coverY + coverS / 2f + 18f)
            }

            var ty = coverY + coverS + 35f

            g.font  = loadingTitleFont
            g.renderColor = RenderColor.of(235, 225, 255)
            g.drawStringCentered(songEntry.song.title, cx.toFloat(), ty); ty += 38f

            g.font  = loadingArtistFont
            g.renderColor = RenderColor.of(150, 125, 200)
            g.drawStringCentered(songEntry.song.artist, cx.toFloat(), ty); ty += 28f

            songEntry.song.bpm?.let {
                g.font  = loadingBpmFont
                g.renderColor = RenderColor.of(110, 95, 140)
                g.drawStringCentered("BPM  $it", cx.toFloat(), ty)
            }

            if (infoAlpha < 1.0f) {
                g.globalAlpha = oldComp
            }
        }

        if (laneAlpha > 0f) {
            val oldComp = g.globalAlpha
            if (laneAlpha < 1.0f) {
                g.globalAlpha = laneAlpha
            }

            g.renderColor = COLOR_OVERLAY
            g.fillRect(0, 0, w, h)

            for (i in 0 until LANE_COUNT) {
                val lx = lanesL + i * LANE_WIDTH
                g.renderColor = if (laneHeld[i]) COLOR_LANE_HELD else COLOR_LANE_NORMAL
                g.fillRect(lx, 0, LANE_WIDTH, h)
                g.renderColor = COLOR_LANE_LINE
                g.drawLine(lx, 0, lx, h)
            }
            g.renderColor = COLOR_LANE_LINE
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
                        g.renderColor = COLOR_LANE_LINE
                        g.drawLine(lanesL, y.toInt(), lanesL + TOTAL_WIDTH, y.toInt())
                    } else {
                        g.renderColor = COLOR_BEAT_LINE
                        g.drawLine(lanesL, y.toInt(), lanesL + TOTAL_WIDTH, y.toInt())
                    }
                }
            }

            val prevStroke = g.stroke
            g.stroke = STROKE_JUDGE
            g.renderColor  = COLOR_JUDGE_LINE
            g.drawLine(lanesL, hl, lanesL + TOTAL_WIDTH, hl)
            g.stroke = prevStroke

            // 레인 키 라벨
            g.font = statFont
            val keyFm = statFontMetrics ?: g.getFontMetrics(statFont).also { statFontMetrics = it }
            for (i in 0 until LANE_COUNT) {
                val lx = lanesL + i * LANE_WIDTH
                g.renderColor = if (laneHeld[i]) RenderColor.WHITE else COLOR_KEY_NORMAL
                g.drawString(KEY_LABELS[i], lx + (LANE_WIDTH - keyFm.stringWidth(KEY_LABELS[i])) / 2, hl + 28)
            }

        // 장식 + 화면 효과 — volatile 필드를 한 번만 읽어 3회 접근 제거
        val dr = decorationRenderer
        if (dr != null) {
            dr.render(g, currentTimeMs, beforeNotes = true)
            dr.render(g, currentTimeMs, beforeNotes = false)
            dr.renderScreenEffects(g, currentTimeMs)
        }

        // 콤보
        if (combo > 0) {
            if (cachedCombo != combo) { cachedCombo = combo; cachedComboStr = combo.toString() }
            g.font  = comboFont
            g.renderColor = RenderColor.WHITE
            val cfm = comboFontMetrics ?: g.getFontMetrics(comboFont).also { comboFontMetrics = it }
            g.drawString(cachedComboStr, (w - cfm.stringWidth(cachedComboStr)) / 2, 96)

            g.font  = statFont
            g.renderColor = COLOR_COMBO_LABEL
            val cl  = "COMBO"
            val sfm = statFontMetrics ?: g.getFontMetrics(statFont).also { statFontMetrics = it }
            g.drawString(cl, (w - sfm.stringWidth(cl)) / 2, 116)
        }

        // 판정 텍스트 (페이드아웃)
        if (judgmentFadeMs > 0 && judgmentText.isNotEmpty()) {
            val alpha = (judgmentFadeMs.toFloat() / JUDGE_FADE_MS).coerceIn(0f, 1f)
            val old   = g.globalAlpha
            g.globalAlpha = alpha
            g.font  = judgeFont
            g.renderColor = judgmentColor
            val jfm = judgeFontMetrics ?: g.getFontMetrics(judgeFont).also { judgeFontMetrics = it }
            g.drawString(judgmentText, (w - jfm.stringWidth(judgmentText)) / 2, hl - 70)
            g.globalAlpha = old
        }

        // 점수 (우상단)
        g.font  = scoreFont
        g.renderColor = RenderColor.WHITE
        if (cachedScore != scoreEngine.score) {
            cachedScore = scoreEngine.score
            cachedScoreStr = "%07d".format(cachedScore)
        }
        val sfm = scoreFontMetrics ?: g.getFontMetrics(scoreFont).also { scoreFontMetrics = it }
        g.drawString(cachedScoreStr, w - sfm.stringWidth(cachedScoreStr) - 20, 38)

        // 판정 카운트 (좌상단)
        g.font  = statFont
        g.renderColor = COLOR_STAT_TEXT
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
        g.renderColor = COLOR_HINT_TEXT
        g.drawString("ESC: Back", 10, h - 10)

        if (laneAlpha < 1.0f) {
            g.globalAlpha = oldComp
        }
        }

        // READY 상태의 3초 카운트다운 (laneAlpha 블록 밖 — 독립 렌더링)
        if (phase == Phase.READY && readyElapsedMs >= 2000.0) {
            renderReadyOverlay(g, w, h)
        }

        // 결과 화면 오버레이 (laneAlpha 블록 밖 — 항상 풀 알파)
        if (phase == Phase.RESULT) renderResult(g, w, h)
    }

    private fun renderResult(g: io.github.jwyoon1220.engine.DrawContext, w: Int, h: Int) {
        g.renderColor = COLOR_RESULT_OVERLAY
        g.fillRect(0, 0, w, h)

        val counts    = scoreEngine.counts
        val score     = scoreEngine.score
        val totalHits = counts[0] + counts[1] + counts[2] + counts[3]
        val accuracy  = if (totalHits > 0)
            (counts[0] * 100.0 + counts[1] * 70.0 + counts[2] * 30.0) / (totalHits * 100.0) * 100.0
        else 0.0
        val rank = when {
            score >= 980_000 -> "SS"
            score >= 950_000 -> "S"
            score >= 900_000 -> "A"
            score >= 800_000 -> "B"
            score >= 600_000 -> "C"
            else             -> "D"
        }
        val rankColor = when (rank) {
            "SS" -> COLOR_RANK_SS
            "S"  -> COLOR_RANK_S
            "A"  -> COLOR_RANK_A
            "B"  -> COLOR_RANK_B
            "C"  -> COLOR_RANK_C
            else -> COLOR_RANK_D
        }

        val cxF = (w / 2).toFloat()
        var y  = h / 2 - 200

        // RESULT 타이틀
        g.font  = resultTitle
        g.renderColor = COLOR_RESULT_TITLE
        g.drawStringCentered("RESULT", cxF, y.toFloat()); y += 68

        // 점수 (글로우)
        val scoreStr = "%07d".format(score)
        g.font  = resultScore
        g.renderColor = RenderColor.of(rankColor.r, rankColor.g, rankColor.b, 40)
        g.setFontBlur(14f); g.drawStringCentered(scoreStr, cxF, y.toFloat()); g.setFontBlur(0f)
        g.renderColor = COLOR_RESULT_SCORE
        g.drawStringCentered(scoreStr, cxF, y.toFloat()); y += 18

        // 랭크 (글로우 + 색상)
        g.font  = resultScore
        g.renderColor = RenderColor.of(rankColor.r, rankColor.g, rankColor.b, 60)
        g.setFontBlur(20f); g.drawStringCentered(rank, cxF, (y + 80).toFloat()); g.setFontBlur(0f)
        g.renderColor = rankColor
        g.drawStringCentered(rank, cxF, (y + 80).toFloat()); y += 150

        // 정확도
        g.font  = resultStat
        g.renderColor = COLOR_RESULT_ACCURACY
        g.drawStringCentered("Accuracy  %.2f%%".format(accuracy), cxF, y.toFloat()); y += 38

        // 판정 카운트
        val line = "PERFECT ${counts[0]}   GREAT ${counts[1]}   GOOD ${counts[2]}   MISS ${counts[3]}"
        g.font  = resultStat
        g.renderColor = COLOR_RESULT_STAT
        g.drawStringCentered(line, cxF, y.toFloat()); y += 38

        // 최대 콤보
        g.font  = resultStat
        g.renderColor = COLOR_RESULT_STAT2
        g.drawStringCentered("MAX COMBO  ${scoreEngine.maxCombo}", cxF, y.toFloat()); y += 52

        // 힌트
        g.font  = resultHint
        g.renderColor = COLOR_RESULT_HINT
        g.drawStringCentered("Enter : 곡 선택으로", cxF, y.toFloat())
    }

    private fun renderReadyOverlay(g: io.github.jwyoon1220.engine.DrawContext, w: Int, h: Int) {
        // "READY" label
        g.font  = readyLabelFont
        g.renderColor = COLOR_READY_LABEL
        val readyLabel = "READY"
        val rfm = readyLabelFontMetrics ?: g.getFontMetrics(readyLabelFont).also { readyLabelFontMetrics = it }
        g.drawString(readyLabel, (w - rfm.stringWidth(readyLabel)) / 2, h / 2 - 20)

        // Countdown number (3, 2, 1, GO!) with pulse
        val remaining = (READY_DURATION_MS - readyElapsedMs).coerceAtLeast(0.0)
        val countStr: String
        val fraction: Double
        if (remaining > 3000.0) {
            countStr = "3"
            fraction = (remaining - 3000.0) / 1000.0
        } else if (remaining > 2000.0) {
            countStr = "2"
            fraction = (remaining - 2000.0) / 1000.0
        } else if (remaining > 1000.0) {
            countStr = "1"
            fraction = (remaining - 1000.0) / 1000.0
        } else {
            countStr = "GO!"
            fraction = remaining / 1000.0
        }
        val alpha = (fraction * 200 + 55).toInt().coerceIn(55, 255)

        g.font  = countdownFont
        val baseColor = if (countStr == "GO!") COLOR_COUNTDOWN_GO else COLOR_RANK_SS
        g.renderColor = RenderColor.of(baseColor.r, baseColor.g, baseColor.b, alpha)
        val cfm = countdownFontMetrics ?: g.getFontMetrics(countdownFont).also { countdownFontMetrics = it }
        g.drawString(countStr, (w - cfm.stringWidth(countStr)) / 2, h / 2 + cfm.ascent - 30)
    }

    override fun collectActiveGlEffects(): List<GlScreenEffectData> =
        decorationRenderer?.collectGlEffects(currentTimeMs) ?: emptyList()

    override fun renderOpenGL(renderer: GlQuadBatchRenderer) {
        ctx.noteRenderer.render(renderer, this)
    }

    override fun keyPressed(key: Int, mods: Int) {
        if (key == Keys.ESCAPE) {
            ctx.sceneRouter.navigate(SongSelectScene(ctx, SelectMode.PLAY))
            return
        }
        if (phase == Phase.RESULT && key == Keys.ENTER) {
            ctx.sceneRouter.navigate(SongSelectScene(ctx, SelectMode.PLAY))
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
        judgmentText      = j.name
        judgmentColor     = judgColor(j)
        judgmentFadeMs    = JUDGE_FADE_MS
        replayLastJudgment = j.name
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

            val jsonStr = REPLAY_MAPPER.writeValueAsString(replay)

            val replayDir = File(songEntry.songDir, "replays").apply { mkdirs() }
            val outputFile = File(replayDir, "golden_replay.json")

            FileWriter(outputFile).use { it.write(jsonStr) }
            println("✓ Replay saved: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            System.err.println("✗ Failed to save replay: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun resolveMediaPath(): String? = songEntry.resolveMediaPath()
}
