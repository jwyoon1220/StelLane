package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.AppSettings
import io.github.jwyoon1220.app.DecorationRenderer
import io.github.jwyoon1220.app.EditorRenderBackend
import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.Decoration
import io.github.jwyoon1220.core.data.DecorationData
import io.github.jwyoon1220.core.data.MutableChart
import io.github.jwyoon1220.core.data.MutableNote
import io.github.jwyoon1220.core.data.NoteType
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.song.ChartParser
import io.github.jwyoon1220.core.song.DecorationParser
import io.github.jwyoon1220.editor.Quantizer
import io.github.jwyoon1220.editor.Timeline
import io.github.jwyoon1220.app.SwingHelper
import io.github.jwyoon1220.engine.CustomGLRenderable
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.GameState
import io.github.jwyoon1220.engine.GlQuadBatchRenderer
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.LaneEventType
import it.unimi.dsi.fastutil.ints.IntArraySet
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.io.File

class EditorState(
    private val ctx: GameContext,
    private val songEntry: SongEntry,
    private val chartFile: File,
    chart: Chart
) : GameState, CustomGLRenderable {
    override val rendersBackground = true
    override val useCustomGlRenderer: Boolean
        get() = AppSettings.editorRenderBackend == EditorRenderBackend.CUSTOM

    // ── 노트 모드 ──────────────────────────────────────────────────────────────
    private enum class NoteMode { NORMAL, LONG }
    private var noteMode = NoteMode.NORMAL
    // ── 채보 데이터 ───────────────────────────────────────────────────────────
    private val mutableChart = MutableChart(
        offsetMs = chart.offsetMs,
        notes    = chart.notes.map { MutableNote(it.time, it.lane, it.type, it.endTime) }.toMutableList()
    )

    // ── 재생 상태 ─────────────────────────────────────────────────────────────
    @Volatile private var isPlaying = false
    private var recordingMode       = false
    private var unsaved             = false

    // ── 퀀타이즈 ─────────────────────────────────────────────────────────────
    private var quantizeEnabled  = true
    private var quantizeDivision = 4

    // ── 줌 ───────────────────────────────────────────────────────────────────
    private var visibleWindowMs  = 6_000L
    private val zoomLevels       = arrayOf(2_000L, 4_000L, 6_000L, 10_000L, 16_000L, 30_000L)
    private var zoomIdx          = 2   // 기본 6s

    // ── 선택 / 클립보드 ───────────────────────────────────────────────────────
    private val selectedIndices  = IntArraySet()
    private var cursorNoteIdx    = -1   // Tab 네비게이션 커서
    private var clipboard        = listOf<MutableNote>()
    private var clipboardBaseMs  = 0L   // 복사 시 최소 time

    // ── 타임라인 가변 스크롤 및 데코레이션 드래그 ────────────────────────────────
    private var timelineScrollMs = 0L
    private var draggingDecorIdx = -1
    private var decorDragOffsetX = 0f
    private var decorDragOffsetY = 0f

    // 노트 리사이즈 드래그
    private var noteResizeIdx   = -1
    private var noteResizeMoved = false

    // 데코레이션 코너 핸들 리사이즈 드래그
    private var resizingDecorIdx  = -1
    private var decorResizePressX = 0
    private var decorResizePressY = 0
    private var decorResizeOrigW  = 0f
    private var decorResizeOrigH  = 0f

    private var timelineDecorDragIdx = -1
    private var timelineDecorDragOffsetX = 0f

    private var pendingSeekMs: Long = -1L
    private var lastSeekTimeMs: Long = 0L
    private var timelineDragStartX = -1

    // ── 실행 취소 / 재실행 ────────────────────────────────────────────────────
    private val undoStack = ArrayDeque<List<MutableNote>>()
    private val redoStack = ArrayDeque<List<MutableNote>>()

    // ── 레이아웃 (render→mouse 공유) ───────────────────────────────────────────
    private var renderW = 1280
    private var renderH = 720
    // 통합 에디터 레이아웃 상수
    private val HDR_H      = 46
    private val PROPS_W    = 260
    private val NOTE_TL_H  = 90
    private val DECOR_TL_H = 110
    private val btmH       get() = NOTE_TL_H + DECOR_TL_H
    private val unifiedTlY get() = renderH - btmH
    private val noteTrackY get() = unifiedTlY + DECOR_TL_H
    private val mainW      get() = renderW - PROPS_W
    // 하위 호환 (findNoteAt, mouseDragged 등에서 사용)
    private val tlY       get() = noteTrackY
    private val tlW       get() = mainW
    private val tlH       get() = NOTE_TL_H
    private val tlCursorX get() = mainW / 2

    // ── 레코딩 (홀드 추적) ────────────────────────────────────────────────────
    private val heldLaneStartMs  = LongArray(4) { -1L }
    // GameLoopThread(update) ↔ EDT(render) 동시 접근 목적: mutableChart.notes 보호
    private val notesLock = Any()

    // ── 데코레이션 편집 모드 ──────────────────────────────────────────────────
    private var decorMode = false
    private var decorData: DecorationData = DecorationParser.parseOrNull(songEntry.songDir) ?: DecorationData()
        set(value) { field = value; decorations = value.decorations.toMutableList() }
    // toMutableList()는 decorData 교체 시에만 호출됨 (getter 매 접근 할당 제거)
    private var decorations: MutableList<Decoration> = decorData.decorations.toMutableList()
    private var selectedDecorIdx = -1
    private var decorRenderer: DecorationRenderer? = null
    // 타임라인 영역 (하단 스트립, decorMode 렌더시 설정)
    private var decorTlX = 0
    private var decorTlY = 0
    private var decorTlW = 0
    private var decorTlH = 0
    // ── 장식 레이어 캐시 (decorMode) ───────────────────────────────────────
    // DrawContext (NanoVG) GPU 가속이므로 CPU-side 캐시 불필요 — 매 프레임 직접 렌더링
    // ── 폰트 ─────────────────────────────────────────────────────────────────
    private val headerFont = FontLoader.semiBold(20f)
    private val infoFont   = FontLoader.regular(15f)
    private val hintFont   = FontLoader.light(12f)
    private val toolFont   = FontLoader.regular(13f)

    // ── 컴패니언 — 매 프레임 Color 할당 방지 ─────────────────────────────────────
    companion object {
        // 타임라인 노트 색상 (Custom GL 패스)
        private val TL_LANE_COLORS = arrayOf(
            Color(100, 180, 255, 200),
            Color(100, 255, 160, 200),
            Color(255, 200, 80,  200),
            Color(255, 120, 120, 200)
        )
        private val TL_SELECTED_COLOR = Color(255, 255, 80, 230)
        // 롱노트 바디 색상 캐시: [lane 0..3][0=normal, 1=selected]
        private val TL_LONG_BODY_COLORS = Array(4) { lane ->
            val c = arrayOf(
                Color(100, 180, 255), Color(100, 255, 160),
                Color(255, 200, 80), Color(255, 120, 120)
            )[lane]
            arrayOf(
                Color(c.red, c.green, c.blue, 120),
                Color(c.red, c.green, c.blue, 200)
            )
        }
        private val TL_SELECTED_BODY_COLOR = Color(255, 255, 80, 200)
        // 단노트 하이라이트(밝은) 버전 색상 캐시
        private val TL_BRIGHT_COLORS = Array(4) { lane ->
            val c = arrayOf(
                Color(100, 180, 255, 200), Color(100, 255, 160, 200),
                Color(255, 200, 80, 200),  Color(255, 120, 120, 200)
            )[lane]
            Color(
                (c.red   * 1.2f).toInt().coerceAtMost(255),
                (c.green * 1.2f).toInt().coerceAtMost(255),
                (c.blue  * 1.2f).toInt().coerceAtMost(255),
                c.alpha
            )
        }
    }

    // ── GameState ─────────────────────────────────────────────────────────────

    override fun enter() {
        isPlaying = false
        ctx.inputManager.clearEvents()
        heldLaneStartMs.fill(-1L)
        selectedIndices.clear()
        cursorNoteIdx = -1
        // 장식 렌더러 항상 초기화 (통합 에디터: 영상 위에 항상 표시)
        decorData = DecorationParser.parseOrNull(songEntry.songDir) ?: DecorationData()
        syncDecorRenderer()
        // 에디터 배경으로 영상 즉시 재생 (비디오 서피스 생성 확인용)
        val path = resolveMediaPath()
        if (path != null) {
            ctx.videoBackground.onPlayingStarted = { isPlaying = true }
            ctx.videoBackground.play(path)
        }
    }

    override fun exit() {
        ctx.videoBackground.onPlayingStarted = null
        ctx.videoBackground.stop()
        ctx.inputManager.clearEvents()
    }

    override fun update(deltaTime: Double) {
        val events = ctx.inputManager.pollEvents()
        
        if (pendingSeekMs >= 0L) {
            val now = System.currentTimeMillis()
            if (now - lastSeekTimeMs > 66) { // ~15 FPS 제한으로 버벅임 방지
                ctx.videoBackground.seek(pendingSeekMs)
                lastSeekTimeMs = now
                pendingSeekMs = -1L
            }
        }
        
        val currentTimeMs = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
        // 재생 중일 때 타임라인 스크롤 자동 전진 (페이지 넘김 방식 또는 부드러운 스크롤)
        if (isPlaying) {
            if (currentTimeMs > timelineScrollMs + visibleWindowMs * 0.9) {
                timelineScrollMs = currentTimeMs - (visibleWindowMs * 0.1).toLong()
            } else if (currentTimeMs < timelineScrollMs) {
                timelineScrollMs = currentTimeMs - (visibleWindowMs * 0.1).toLong()
            }
        }
        if (recordingMode && isPlaying) {
            val now = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
            for (event in events) {
                val lane = event.lane
                when (event.type) {
                    LaneEventType.PRESS   -> heldLaneStartMs[lane] = now
                    LaneEventType.RELEASE -> {
                        val startMs = heldLaneStartMs[lane]
                        if (startMs < 0) continue
                        val duration     = now - startMs
                        val snappedStart = snapTime(startMs)

                        saveSnapshot()
                        synchronized(notesLock) {
                            if (duration < 150 || noteMode == NoteMode.NORMAL) {
                                mutableChart.notes.add(MutableNote(snappedStart, lane, NoteType.SHORT))
                            } else {
                                mutableChart.notes.add(MutableNote(snappedStart, lane, NoteType.LONG, snapTime(now)))
                            }
                            mutableChart.notes.sortBy { it.time }
                        }
                        heldLaneStartMs[lane] = -1L
                        unsaved = true
                    }
                }
            }
        }
    }

    override fun render(g: DrawContext) {
        renderW = g.clipBounds.width
        renderH = g.clipBounds.height
        val w = renderW
        val h = renderH

        val currentTimeMs = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
        val pos     = maxOf(currentTimeMs, 0L)
        val timeStr = "%d:%02d.%03d".format(pos / 60_000, (pos % 60_000) / 1000, pos % 1000)

        // ── 레이아웃 계산 ──────────────────────────────────────────────────────
        val propsX = w - PROPS_W
        val tl0Y   = unifiedTlY
        val noteY  = noteTrackY
        // handleDecorMouseClick 좌표 갱신
        decorTlX = 0; decorTlY = tl0Y; decorTlW = mainW; decorTlH = DECOR_TL_H

        // ── 0. 전체 배경 검은색 채우기 ────────────────────────────────────────
        g.color = Color(10, 10, 12) // 매우 어두운 바탕색
        g.fillRect(0, 0, w, h)

        // ── 1. 16:9 뷰포트 계산 및 비디오/장식 렌더링 ────────────────────────
        val vpH = h - HDR_H - btmH
        val vpW = (vpH * 16 / 9)
        val vpX = (mainW - vpW) / 2
        val vpY = HDR_H

        // 비디오 프레임 렌더링: GL 텍스처 핸들 우선(uploadPendingFrame이 이미 최신 상태 보장),
        // 없으면 BufferedImage 폴백. CUSTOM GL 모드에서는 renderCustomGl()에서 직접 GL 텍스처를
        // 그리므로 NanoVG 비디오 패스를 건너뜀.
        if (!useCustomGlRenderer) {
            val nvgHandle = ctx.videoBackground.getNvgImageHandle(g.vg)
            if (nvgHandle >= 0) {
                g.drawNvgImage(nvgHandle, vpX.toFloat(), vpY.toFloat(), vpW.toFloat(), vpH.toFloat())
            } else {
                val frame = ctx.videoBackground.getCurrentFrame()
                if (frame != null) g.drawImage(frame, vpX, vpY, vpW, vpH, null)
            }
        }

        // ── 2. 장식 오버레이 (뷰포트 스케일 적용) ────────────────────────────────
        val renderer = decorRenderer
        if (renderer != null) {
            g.save()
            g.setClip(vpX, vpY, vpW, vpH)
            g.translate(vpX.toDouble(), vpY.toDouble())
            g.scale(vpW / 1280.0, vpH / 720.0)
            renderer.render(g, currentTimeMs, beforeNotes = true)
            renderer.render(g, currentTimeMs, beforeNotes = false)
            renderer.renderScreenEffects(g, currentTimeMs)
            g.restore()
        }

        // ── 3. 선택된 장식 바운딩 박스 (뷰포트 적용) ──────────────────────────
        if (decorMode) {
            val selDec = decorations.getOrNull(selectedDecorIdx)
            selDec?.let { dec ->
                // 데코레이션은 1280x720 논리 해상도를 기준으로 하므로,
                // 이를 뷰포트 비율로 변환해야 함
                val scaleRatioX = vpW / 1280f
                val scaleRatioY = vpH / 720f

                val bw = if (dec.width  <= 1f) dec.width  * 1280f else dec.width
                val bh = if (dec.height <= 1f) dec.height * 720f  else dec.height
                val fw = (bw * scaleRatioX).toInt().coerceAtLeast(1)
                val fh = (bh * scaleRatioY).toInt().coerceAtLeast(1)
                
                val lx = vpX + ((dec.x * 1280f - dec.pivotX * bw) * scaleRatioX).toInt()
                val lt = vpY + ((dec.y * 720f  - dec.pivotY * bh) * scaleRatioY).toInt()

                val sc  = g.stroke; val sc2 = g.composite
                g.composite = AlphaComposite.SrcOver
                g.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, floatArrayOf(8f, 4f), 0f)
                g.color = Color(255, 220, 60, 220)
                g.drawRect(lx, lt, fw, fh)
                g.stroke = BasicStroke(2f)
                val hs = 8
                for ((hx, hy) in listOf(lx to lt, lx + fw to lt, lx to lt + fh, lx + fw to lt + fh)) {
                    g.color = Color(255, 220, 60, 200); g.fillRect(hx - hs/2, hy - hs/2, hs, hs)
                }
                g.stroke = sc; g.composite = sc2
            }
        }

        // ── 4. UI 영역 반투명 배경 ────────────────────────────────────────────
        val origCmp = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.90f)
        g.color = Color(8, 5, 20)
        g.fillRect(0, 0, w, HDR_H)
        g.fillRect(0, tl0Y, w, h - tl0Y)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.82f)
        g.fillRect(propsX, HDR_H, PROPS_W, tl0Y - HDR_H)
        g.composite = origCmp

        // ── 5. 헤더 ────────────────────────────────────────────────────────────
        g.color = Color(70, 42, 112, 200)
        g.drawLine(0, HDR_H, w, HDR_H)
        g.font  = headerFont
        g.color = Color(200, 160, 255)
        g.drawString("✏  ${songEntry.song.title}", 14, HDR_H - 28)
        g.font  = infoFont
        g.color = Color.WHITE
        val modeTag  = if (decorMode) "田 DECOR" else "♩ NOTE"
        val stateStr = buildString {
            append(timeStr)
            append(if (isPlaying) "   ▶" else "   ⏸")
            append("   $modeTag")
            if (recordingMode) append("   ⏺REC")
            append(if (quantizeEnabled) "   Snap 1/$quantizeDivision" else "   Free")
            append("   ${visibleWindowMs / 1000}s")
            if (unsaved) append("   ●")
        }
        g.drawString(stateStr, 14, HDR_H - 10)

        // ── 6. 노트 타임라인 ─────────────────────────────────────────────────
        val chartSnapshot = synchronized(notesLock) {
            MutableChart(mutableChart.offsetMs, mutableChart.notes.toMutableList())
        }
        Timeline.render(
            g, chartSnapshot, timelineScrollMs, currentTimeMs,
            0, noteY, mainW, NOTE_TL_H,
            visibleWindowMs, selectedIndices,
            songEntry.song.bpm?.toDouble(),
            drawNotes = !useCustomGlRenderer  // CUSTOM GL 모드: renderCustomGl()에서 GL로 렌더
        )
        g.font = hintFont; g.color = Color(90, 75, 125)
        g.drawString("♩ NOTES", 4, noteY - 3)

        // ── 7. 장식 타임라인 ─────────────────────────────────────────────────
        val decorList = decorations
        val viewMs    = visibleWindowMs
        val stepMs    = (viewMs / 8).coerceAtLeast(100L)
        val gridStart = (timelineScrollMs / stepMs) * stepMs
        g.font = hintFont
        for (i in 0..16) {
            val t  = gridStart + i * stepMs
            val px = ((t - timelineScrollMs).toDouble() / viewMs * mainW).toInt()
            if (px < 0 || px > mainW) continue
            g.color = Color(42, 32, 62, 180)
            g.drawLine(px, tl0Y, px, tl0Y + DECOR_TL_H)
            if (t >= 0L) { g.color = Color(80, 65, 115); g.drawString("${t / 1000}s", px + 2, tl0Y + 13) }
        }
        val decorLaneH = ((DECOR_TL_H - 20) / 5).coerceAtLeast(8)
        decorList.forEachIndexed { idx, dec ->
            val lane    = idx % 5
            val by      = tl0Y + 18 + lane * decorLaneH
            val startPx = ((dec.timeMs - timelineScrollMs).toDouble() / viewMs * mainW).toInt()
            val endPx   = ((dec.timeMs + dec.durationMs - timelineScrollMs).toDouble() / viewMs * mainW).toInt()
            val barX    = startPx.coerceAtLeast(0)
            val barW2   = (endPx - barX).coerceIn(4, mainW)
            val hue     = (idx * 53) % 360
            val bright  = if (idx == selectedDecorIdx) 1.0f else 0.65f
            g.color = java.awt.Color.getHSBColor(hue / 360f, 0.78f, bright)
            g.fillRoundRect(barX, by, barW2, decorLaneH - 2, 4, 4)
            if (idx == selectedDecorIdx) { g.color = Color.WHITE; g.drawRoundRect(barX, by, barW2, decorLaneH - 2, 4, 4) }
            if (barW2 > 24) {
                g.color = Color(0, 0, 0, 175); g.font = hintFont
                g.drawString(dec.id.ifEmpty { "#$idx" }, barX + 3, by + decorLaneH - 3)
            }
        }
        g.color = Color(90, 75, 125); g.font = hintFont
        g.drawString("田 DECOR", 4, tl0Y + 13)

        // 트랙 구분선
        g.color = Color(55, 42, 80, 200)
        g.drawLine(0, noteY, mainW, noteY)
        g.drawLine(0, tl0Y, w, tl0Y)

        // 플레이헤드
        val phX = ((currentTimeMs - timelineScrollMs).toDouble() / viewMs * mainW).toInt()
        if (phX in 0..mainW) {
            g.color = Color(210, 100, 255)
            g.drawLine(phX, tl0Y, phX, h)
            g.fillPolygon(intArrayOf(phX - 5, phX + 5, phX), intArrayOf(tl0Y, tl0Y, tl0Y + 9), 3)
        }

        // ── 8. 우측 패널 ────────────────────────────────────────────────────
        g.color = Color(60, 42, 90, 180)
        g.drawLine(propsX, HDR_H, propsX, h)
        if (decorMode) renderDecorPropsPanel(g, propsX, HDR_H, PROPS_W, tl0Y - HDR_H)
        else           renderNoteShortcutPanel(g, propsX, HDR_H, PROPS_W, tl0Y - HDR_H)
    }

    override fun keyPressed(key: Int, mods: Int) {
        val ctrl  = Keys.isCtrl(mods)
        val shift = Keys.isShift(mods)

        // 데코레이션 모드 전용 단축키
        if (decorMode) {
            when {
                ctrl && shift && key == Keys.D -> toggleDecorMode()
                ctrl && key == Keys.S          -> saveDecor()
                key == Keys.E && selectedDecorIdx >= 0 ->
                    javax.swing.SwingUtilities.invokeLater { openDecorEditDialog(selectedDecorIdx) }
                key == Keys.DELETE || key == Keys.BACKSPACE -> {
                    val idx = selectedDecorIdx
                    if (idx >= 0) {
                        val cur = decorations; cur.removeAt(idx)
                        decorData = DecorationData(cur, decorData.screenEffects)
                        selectedDecorIdx = (idx - 1).coerceAtLeast(-1)
                    }
                }
                key == Keys.ESCAPE -> decorMode = false
                // 재생 제어는 데코레이션 모드에서도 허용
                key == Keys.SPACE  -> togglePlay()
                key == Keys.LEFT   -> seek(-1_000L)
                key == Keys.RIGHT  -> seek(1_000L)
            }
            return
        }

        when {
            ctrl && shift && key == Keys.D -> toggleDecorMode()

            // 재생 제어 — 녹음 중에는 J/K 가 레인 키로만 동작
            key == Keys.SPACE                       -> togglePlay()
            key == Keys.J && !ctrl && !recordingMode -> { pause(); seek(-5_000L) }
            key == Keys.K && !recordingMode          -> pause()
            key == Keys.L && !ctrl                  -> startPlay()
            key == Keys.HOME                        -> seek(-ctx.videoBackground.getSmoothTimeMs())
            key == Keys.END                         -> seekToEnd()
            key == Keys.LEFT  && shift              -> seek(-100L)
            key == Keys.RIGHT && shift              -> seek(100L)
            key == Keys.LEFT  && !ctrl && !shift    -> seek(-1_000L)
            key == Keys.RIGHT && !ctrl && !shift    -> seek(1_000L)

            // 선택
            ctrl && key == Keys.A                   -> selectAll()
            ctrl && key == Keys.D                   -> selectedIndices.clear()
            key == Keys.TAB && !shift               -> navigateNote(+1)
            key == Keys.TAB && shift                -> navigateNote(-1)

            // 편집
            ctrl && key == Keys.Z && !shift         -> undo()
            ctrl && key == Keys.Y                   -> redo()
            ctrl && key == Keys.Z && shift          -> redo()
            ctrl && key == Keys.C                   -> copy()
            ctrl && key == Keys.X                   -> cut()
            ctrl && key == Keys.V                   -> paste()
            key == Keys.DELETE || key == Keys.BACKSPACE -> deleteSelected()

            // 녹음 / 퀀타이즈
            key == Keys.N                           -> cycleNoteMode()
            key == Keys.R                           -> {
                recordingMode = !recordingMode
                heldLaneStartMs.fill(-1L)
            }
            key == Keys.Q                           -> cycleQuantize()
            key == Keys.N4                          -> { quantizeEnabled = true; quantizeDivision = 4  }
            key == Keys.N8                          -> { quantizeEnabled = true; quantizeDivision = 8  }
            key == Keys.N6                          -> { quantizeEnabled = true; quantizeDivision = 16 }

            // 줌
            key == Keys.EQUAL || key == Keys.PLUS   -> zoomIn()
            key == Keys.MINUS                       -> zoomOut()

            // 저장 / 뒤로
            ctrl && key == Keys.S                   -> if (decorMode) saveDecor() else save()
            ctrl && key == Keys.COMMA               -> openSettings()
            ctrl && shift && key == Keys.O          -> openCalibration()
            key == Keys.ESCAPE                      -> {
                if (selectedIndices.isNotEmpty()) selectedIndices.clear()
                else ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.EDIT))
            }
        }
    }

    override fun keyTyped(codepoint: Int) {
    }

    // ── 재생 제어 ─────────────────────────────────────────────────────────────

    private fun togglePlay() {
        if (isPlaying) pause() else startPlay()
    }

    private fun startPlay() {
        if (!isPlaying) {
            val path = resolveMediaPath() ?: return
            if (ctx.videoBackground.isPlayable()) {
                val currentMs = ctx.videoBackground.getTimeMs()
                val lengthMs = ctx.videoBackground.getLengthMs()
                // 미디어가 끝에 도달했다면 처음부터 다시 재생
                if (lengthMs > 0L && currentMs >= lengthMs - 100) {
                    ctx.videoBackground.play(path)
                } else {
                    ctx.videoBackground.resume()
                }
            } else {
                ctx.videoBackground.play(path)
            }
            isPlaying = true
        }
    }

    private fun pause() {
        if (isPlaying) {
            ctx.videoBackground.pause()
            isPlaying = false
            recordingMode = false
            heldLaneStartMs.fill(-1L)
        }
    }

    private fun seek(deltaMs: Long) {
        val cur = ctx.videoBackground.getSmoothTimeMs()
        ctx.videoBackground.seek(maxOf(0L, cur + deltaMs))
    }

    private fun seekToEnd() {
        val lastNote = mutableChart.notes.maxOfOrNull { it.endTime ?: it.time } ?: return
        ctx.videoBackground.seek(lastNote + mutableChart.offsetMs + 2_000L)
    }

    // ── 선택 ─────────────────────────────────────────────────────────────────

    private fun selectAll() {
        selectedIndices.clear()
        selectedIndices.addAll(mutableChart.notes.indices)
        cursorNoteIdx = mutableChart.notes.lastIndex
    }

    private fun navigateNote(direction: Int) {
        if (mutableChart.notes.isEmpty()) return
        cursorNoteIdx = (cursorNoteIdx + direction).coerceIn(0, mutableChart.notes.lastIndex)
        selectedIndices.clear()
        selectedIndices.add(cursorNoteIdx)
        // 타임라인 뷰를 선택한 노트로 이동
        val noteTime = mutableChart.notes[cursorNoteIdx].time + mutableChart.offsetMs
        ctx.videoBackground.seek(noteTime)
    }

    // ── 편집 (undo/redo/copy/cut/paste/delete) ────────────────────────────────

    private fun saveSnapshot() {
        undoStack.addLast(mutableChart.notes.map { it.copy() })
        redoStack.clear()
        if (undoStack.size > 50) undoStack.removeFirst()
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(mutableChart.notes.map { it.copy() })
        mutableChart.notes.clear()
        mutableChart.notes.addAll(undoStack.removeLast())
        selectedIndices.clear()
        unsaved = true
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(mutableChart.notes.map { it.copy() })
        mutableChart.notes.clear()
        mutableChart.notes.addAll(redoStack.removeLast())
        selectedIndices.clear()
        unsaved = true
    }

    private fun copy() {
        if (selectedIndices.isEmpty()) return
        val sorted = selectedIndices.sorted().map { mutableChart.notes[it] }
        clipboardBaseMs = sorted.minOf { it.time }
        clipboard = sorted.map { it.copy() }
    }

    private fun cut() {
        copy()
        deleteSelected()
    }

    private fun paste() {
        if (clipboard.isEmpty()) return
        val currentMs = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
        val delta     = currentMs - clipboardBaseMs
        saveSnapshot()
        val pasted = clipboard.map {
            it.copy(time = snapTime(it.time + delta), endTime = it.endTime?.let { e -> snapTime(e + delta) })
        }
        val insertStart = mutableChart.notes.size
        mutableChart.notes.addAll(pasted)
        mutableChart.notes.sortBy { it.time }
        // 붙여넣은 노트들을 선택 상태로
        selectedIndices.clear()
        pasted.forEach { p ->
            val idx = mutableChart.notes.indexOfFirst { it.time == p.time && it.lane == p.lane && it.type == p.type }
            if (idx >= 0) selectedIndices.add(idx)
        }
        unsaved = true
    }

    private fun deleteSelected() {
        if (selectedIndices.isEmpty()) return
        saveSnapshot()
        val sorted = selectedIndices.sortedDescending()
        sorted.forEach { mutableChart.notes.removeAt(it) }
        selectedIndices.clear()
        cursorNoteIdx = -1
        unsaved = true
    }

    // ── 양자화 / 줌 ────────────────────────────────────────────────────────

    private fun cycleQuantize() {
        val options = listOf(4, 8, 16)
        if (!quantizeEnabled) { quantizeEnabled = true; quantizeDivision = 4; return }
        val idx = options.indexOf(quantizeDivision)
        if (idx == options.lastIndex) quantizeEnabled = false
        else quantizeDivision = options[idx + 1]
    }

    private fun snapTime(timeMs: Long): Long {
        if (!quantizeEnabled) return timeMs
        val bpm = songEntry.song.bpm ?: return timeMs
        return Quantizer.snap(timeMs, bpm, quantizeDivision)
    }

    private fun zoomIn() {
        if (zoomIdx > 0) { zoomIdx--; visibleWindowMs = zoomLevels[zoomIdx] }
    }

    private fun zoomOut() {
        if (zoomIdx < zoomLevels.lastIndex) { zoomIdx++; visibleWindowMs = zoomLevels[zoomIdx] }
    }

    // ── 마우스 ────────────────────────────────────────────────────────────────

    override fun mousePressed(x: Float, y: Float, button: Int, mods: Int) {
        val mx = x.toInt(); val my = y.toInt()
        timelineDragStartX = mx
        if (decorMode) {
            val vpH = renderH - HDR_H - btmH
            val vpW = (vpH * 16 / 9)
            val vpX = (mainW - vpW) / 2
            val vpY = HDR_H
            if (mx in vpX..(vpX+vpW) && my in vpY..(vpY+vpH)) {
                val scaleRatioX = vpW / 1280f
                val scaleRatioY = vpH / 720f
                val selDec = decorations.getOrNull(selectedDecorIdx)
                if (selDec != null) {
                    val bw = if (selDec.width <= 1f) selDec.width * 1280f else selDec.width
                    val bh = if (selDec.height <= 1f) selDec.height * 720f else selDec.height
                    val fw = (bw * scaleRatioX).toInt().coerceAtLeast(1)
                    val fh = (bh * scaleRatioY).toInt().coerceAtLeast(1)
                    val lx = vpX + ((selDec.x * 1280f - selDec.pivotX * bw) * scaleRatioX).toInt()
                    val lt = vpY + ((selDec.y * 720f  - selDec.pivotY * bh) * scaleRatioY).toInt()
                    // 코너 핸들 히트 체크 (리사이즈 우선)
                    val hs = 10
                    val corners = listOf(
                        lx        to lt,
                        lx + fw   to lt,
                        lx        to lt + fh,
                        lx + fw   to lt + fh
                    )
                    val hitCorner = corners.indexOfFirst { (hx, hy) ->
                        mx in (hx - hs)..(hx + hs) && my in (hy - hs)..(hy + hs)
                    }
                    if (hitCorner >= 0) {
                        resizingDecorIdx  = selectedDecorIdx
                        decorResizePressX = mx
                        decorResizePressY = my
                        decorResizeOrigW  = selDec.width
                        decorResizeOrigH  = selDec.height
                        return
                    }
                    // 바디 드래그 (코너 바깥이면)
                    if (mx in lx..(lx+fw) && my in lt..(lt+fh)) {
                        draggingDecorIdx = selectedDecorIdx
                        decorDragOffsetX = selDec.x - (mx - vpX) / (1280f * scaleRatioX)
                        decorDragOffsetY = selDec.y - (my - vpY) / (720f * scaleRatioY)
                        return
                    }
                }
            }
        }

        // 데코레이션 타임라인 영역 (하단 스트립) 클릭 → 타임 드래그
        if (decorMode && my in decorTlY..(decorTlY + decorTlH) && mx <= mainW) {
            val laneH = ((decorTlH - 20) / 5).coerceAtLeast(8)
            var hitIdx = -1
            decorations.forEachIndexed { idx, dec ->
                val lane = idx % 5
                val by = decorTlY + 18 + lane * laneH
                val startPx = ((dec.timeMs - timelineScrollMs).toDouble() / visibleWindowMs * mainW).toInt()
                val endPx = ((dec.timeMs + dec.durationMs - timelineScrollMs).toDouble() / visibleWindowMs * mainW).toInt()
                if (mx in startPx.coerceAtLeast(0)..endPx.coerceAtMost(mainW) && my in by..(by + laneH - 3)) {
                    hitIdx = idx
                }
            }
            if (hitIdx >= 0) {
                timelineDecorDragIdx = hitIdx
                val startPx = ((decorations[hitIdx].timeMs - timelineScrollMs).toDouble() / visibleWindowMs * mainW).toInt()
                timelineDecorDragOffsetX = (mx - startPx).toFloat()
                selectedDecorIdx = hitIdx
                return
            }
        }

        // 노트 트랙 영역 내 좌클릭 → 노트 리사이즈 준비
        if (button == Keys.MOUSE_LEFT && my >= tlY && my <= tlY + tlH && mx <= tlW) {
            val currentTimeMs = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
            val laneH = tlH / 4
            val hitIdx = findNoteAt(mx, my, currentTimeMs, laneH)
            if (hitIdx >= 0) {
                noteResizeIdx   = hitIdx
                noteResizeMoved = false
            }
        }
    }

    override fun mouseReleased(x: Float, y: Float, button: Int, mods: Int) {
        // 노트 리사이즈 완료 시 스냅샷 저장
        if (noteResizeIdx >= 0 && noteResizeMoved) {
            saveSnapshot()
            unsaved = true
        }
        noteResizeIdx   = -1
        noteResizeMoved = false
        draggingDecorIdx = -1
        resizingDecorIdx = -1
        timelineDecorDragIdx = -1
        if (pendingSeekMs >= 0L) {
            ctx.videoBackground.seek(pendingSeekMs)
            pendingSeekMs = -1L
        }
    }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        val mx = x.toInt()
        val my = y.toInt()

        // 장식 타임라인 영역 클릭 (어떤 모드든 처리)
        if (my in unifiedTlY..(unifiedTlY + DECOR_TL_H) && mx <= mainW) {
            handleDecorMouseClick(mx, my, button, mods); return
        }

        // 노트 타임라인 영역 안에서만 처리
        if (my < tlY || my > tlY + tlH) return
        if (mx > tlW)                   return

        val currentTimeMs = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
        val laneH = tlH / 4

        when (button) {
            Keys.MOUSE_LEFT -> {
                val hitIdx = findNoteAt(mx, my, currentTimeMs, laneH)
                if (hitIdx >= 0) {
                    // 노트 클릭: 선택 / 추가 선택
                    if (Keys.isShift(mods)) {
                        if (hitIdx in selectedIndices) selectedIndices.remove(hitIdx)
                        else selectedIndices.add(hitIdx)
                    } else {
                        selectedIndices.clear()
                        selectedIndices.add(hitIdx)
                        cursorNoteIdx = hitIdx
                    }
                } else {
                    // 빈 공간 클릭: 플레이헤드 이동 + 선택 해제
                    selectedIndices.clear()
                    val clickMs = timelineScrollMs + mx.toLong() * visibleWindowMs / tlW
                    ctx.videoBackground.seek(maxOf(0L, clickMs + mutableChart.offsetMs))
                }
            }
            Keys.MOUSE_RIGHT -> {
                val hitIdx = findNoteAt(mx, my, currentTimeMs, laneH)
                if (hitIdx >= 0) {
                    // 노트 우클릭: 삭제
                    saveSnapshot()
                    mutableChart.notes.removeAt(hitIdx)
                    val above = selectedIndices.filter { it > hitIdx }.toSet()
                    selectedIndices.removeAll { it >= hitIdx }
                    selectedIndices.addAll(above.map { it - 1 })
                    unsaved = true
                } else {
                    // 빈 공간 우클릭: 노트 추가 팝업
                    val lane    = ((my - tlY).coerceIn(0, tlH - 1) / laneH).coerceIn(0, 3)
                    val clickMs = timelineScrollMs + mx.toLong() * visibleWindowMs / tlW
                    val snapped = snapTime(clickMs.coerceAtLeast(0L))
                    val laneNames = arrayOf("D", "F", "J", "K")
                    val popup = javax.swing.JPopupMenu()
                    popup.add(javax.swing.JMenuItem("${laneNames[lane]} 레인에 노트 추가")).addActionListener {
                        saveSnapshot()
                        val type    = if (noteMode == NoteMode.LONG) NoteType.LONG else NoteType.SHORT
                        val endTime = if (type == NoteType.LONG) snapped + 500L else null
                        mutableChart.notes.add(MutableNote(snapped, lane, type, endTime))
                        mutableChart.notes.sortBy { it.time }
                        unsaved = true
                    }
                    SwingHelper.showPopup(popup, ctx.windowManager.glfwWindow)
                }
            }
        }
    }

    /**
     * 화면 좌표 (mx, my) 에서 노트를 찾아 인덱스를 반환합니다.
     * 노트 x ±6px, 레인 y 범위 내에 있으면 히트로 판정합니다.
     */
    private fun findNoteAt(mx: Int, my: Int, currentTimeMs: Long, laneH: Int): Int {
        mutableChart.notes.forEachIndexed { idx, note ->
            val nx = ((note.time - timelineScrollMs).toDouble() / visibleWindowMs * tlW).toInt()
            if (mx < nx - 6 || mx > nx + 6) return@forEachIndexed
            val ly = tlY + note.lane * laneH
            if (my < ly || my > ly + laneH) return@forEachIndexed
            return idx
        }
        return -1
    }

    override fun mouseDragged(x: Float, y: Float, button: Int) {
        val mx = x.toInt(); val my = y.toInt()
        // 데코레이션 코너 리사이즈
        if (resizingDecorIdx >= 0 && decorMode) {
            val vpH = renderH - HDR_H - btmH
            val vpW = (vpH * 16 / 9)
            val dx = (mx - decorResizePressX).toFloat()
            val dy = (my - decorResizePressY).toFloat()
            val dec = decorations.getOrNull(resizingDecorIdx) ?: run { resizingDecorIdx = -1; return }
            val newW = if (decorResizeOrigW <= 1f) {
                (decorResizeOrigW + dx / vpW).coerceAtLeast(0.02f)
            } else {
                (decorResizeOrigW + dx * 1280f / vpW).coerceAtLeast(1f)
            }
            val newH = if (decorResizeOrigH <= 1f) {
                (decorResizeOrigH + dy / vpH).coerceAtLeast(0.02f)
            } else {
                (decorResizeOrigH + dy * 720f / vpH).coerceAtLeast(1f)
            }
            val updated = dec.copy(width = newW, height = newH)
            val cur = decorData.decorations.toMutableList()
            cur[resizingDecorIdx] = updated
            decorData = DecorationData(cur, decorData.screenEffects)
            syncDecorRenderer()
            unsaved = true
            return
        }
        if (draggingDecorIdx >= 0 && decorMode) {
            val vpH = renderH - HDR_H - btmH
            val vpW = (vpH * 16 / 9)
            val vpX = (mainW - vpW) / 2
            val vpY = HDR_H
            val scaleRatioX = vpW / 1280f
            val scaleRatioY = vpH / 720f
            
            val newX = (mx - vpX) / (1280f * scaleRatioX) + decorDragOffsetX
            val newY = (my - vpY) / (720f * scaleRatioY) + decorDragOffsetY
            
            val dec = decorations[draggingDecorIdx]
            val updated = dec.copy(x = newX.coerceIn(0f, 1f), y = newY.coerceIn(0f, 1f))
            val cur = decorData.decorations.toMutableList()
            cur[draggingDecorIdx] = updated
            decorData = DecorationData(cur, decorData.screenEffects)
            syncDecorRenderer()
            unsaved = true
            return
        }
        
        // 타임라인 내 데코레이션 드래그 (시간 변경)
        if (timelineDecorDragIdx >= 0 && decorMode) {
            val dec = decorations.getOrNull(timelineDecorDragIdx) ?: run { timelineDecorDragIdx = -1; return }
            val newStartPx = mx - timelineDecorDragOffsetX
            val newTimeMs = timelineScrollMs + (newStartPx.toDouble() / mainW * visibleWindowMs).toLong()
            val snappedTime = snapTime(newTimeMs.coerceAtLeast(0L))
            
            val updated = dec.copy(timeMs = snappedTime)
            val cur = decorData.decorations.toMutableList()
            cur[timelineDecorDragIdx] = updated
            decorData = DecorationData(cur, decorData.screenEffects)
            syncDecorRenderer()
            unsaved = true
            return
        }
        
        // 노트 리사이즈 (endTime 연장)
        if (noteResizeIdx >= 0 && button == Keys.MOUSE_LEFT) {
            val note = mutableChart.notes.getOrNull(noteResizeIdx)
            if (note != null) {
                val newEndMs = (timelineScrollMs + mx.toLong() * visibleWindowMs / tlW)
                    .coerceAtLeast(note.time + 50L)
                val snapped = snapTime(newEndMs)
                synchronized(notesLock) {
                    note.type    = NoteType.LONG
                    note.endTime = snapped
                }
                noteResizeMoved = true
            } else {
                noteResizeIdx = -1
            }
            return
        }

        if (my < unifiedTlY || mx > mainW) return
        
        if (button == Keys.MOUSE_MIDDLE || button == Keys.MOUSE_RIGHT) {
            // 패닝 (Panning): 플레이헤드는 그대로 두고 뷰포트만 좌우 이동
            if (timelineDragStartX >= 0) {
                val dx = mx - timelineDragStartX
                timelineDragStartX = mx
                timelineScrollMs -= (dx.toLong() * visibleWindowMs / mainW)
            }
        } else if (button == Keys.MOUSE_LEFT) {
            // 스크러빙 (Scrubbing): 클릭한 위치로 플레이헤드 이동 (VLC 과부하 방지)
            val clickMs = timelineScrollMs + mx.toLong() * visibleWindowMs / mainW
            pendingSeekMs = maxOf(0L, clickMs + mutableChart.offsetMs)
            timelineDragStartX = mx
        }
    }

    // ── 설정 / 보정 다이얼로그 ─────────────────────────────────────────────────

    private fun openSettings() {
        ctx.stateManager.changeState(SettingsState(ctx, this))
    }

    private fun openCalibration() {
        ctx.stateManager.changeState(SettingsState(ctx, this, startAt = 1))
    }

    private fun cycleNoteMode() {
        noteMode = if (noteMode == NoteMode.NORMAL) NoteMode.LONG else NoteMode.NORMAL
    }

    // ── 저장 / 미디어 ────────────────────────────────────────────────────────

    private fun save() {
        runCatching { ChartParser.serializeChart(mutableChart.toChart(), chartFile) }
            .onSuccess { unsaved = false }
    }

    private fun saveDecor() {
        val list = decorations
        decorData = DecorationData(decorations = list, screenEffects = decorData.screenEffects)
        syncDecorRenderer()
        runCatching { DecorationParser.serialize(decorData, songEntry.songDir) }
            .onSuccess {
                javax.swing.JOptionPane.showMessageDialog(null,
                    "decoration.json 저장 완료", "저장", javax.swing.JOptionPane.INFORMATION_MESSAGE)
            }
    }

    private fun syncDecorRenderer() {
        decorRenderer = DecorationRenderer(decorData, songEntry.songDir)
    }

    private fun toggleDecorMode() {
        decorMode = !decorMode
        selectedDecorIdx = -1
        if (decorMode) {
            decorData = DecorationParser.parseOrNull(songEntry.songDir) ?: DecorationData()
            syncDecorRenderer()
        }
        // decorRenderer는 항상 유지 (통합 에디터: 장식 오버레이 항상 표시)
    }

    private fun renderNoteShortcutPanel(g: DrawContext, px: Int, py: Int, pw: Int, ph: Int) {
        g.font = toolFont
        var ty = py + 18
        fun section(title: String) {
            g.color = Color(130, 100, 200); g.drawString(title, px + 10, ty); ty += 20
            g.color = Color(40, 40, 65); g.drawLine(px + 8, ty - 4, px + pw - 4, ty - 4); ty += 4
        }
        fun shortcut(keys: String, desc: String) {
            g.color = Color(255, 220, 80); g.drawString(keys, px + 10, ty)
            g.color = Color(180, 180, 200); g.drawString(desc, px + 90, ty); ty += 18
        }
        section("재생 / 탐색")
        shortcut("Space", "재생 / 일시정지")
        shortcut("J", "5초 뒤로"); shortcut("← / →", "±1초")
        shortcut("Shift←→", "±100ms"); shortcut("Home / End", "시작 / 끝")
        ty += 4
        section("선택 / 편집")
        shortcut("Ctrl+A", "전체 선택"); shortcut("Tab / ↑Tab", "노트 이동")
        shortcut("Ctrl+Z / Y", "취소 / 재실행")
        shortcut("Ctrl+C / V", "복사 / 붙여넣기"); shortcut("Delete", "삭제")
        ty += 4
        section("노트 / 퀘다이즈")
        shortcut("R", "녹음 모드"); shortcut("N", "노트 타입")
        shortcut("Q / 4 / 8 / 6", "스냅"); shortcut("= / -", "줌 in / out")
        ty += 4
        section("기타")
        shortcut("Ctrl+S", "저장")
        shortcut("Ctrl+Shift+D", "장식 모드")
        shortcut("Esc", "뒤로")
    }

    private fun renderDecorPropsPanel(g: DrawContext, px: Int, py: Int, pw: Int, ph: Int) {
        val list   = decorations
        val selDec = list.getOrNull(selectedDecorIdx)
        g.font = toolFont
        var ty = py + 18
        g.color = Color(195, 130, 255); g.font = headerFont
        g.drawString("田 장식 편집", px + 8, ty); ty += 24

        g.font = hintFont; g.color = Color(100, 85, 140)
        g.drawString("Ctrl+Shift+D: 노트 모드   Ctrl+S: 저장", px + 8, ty); ty += 16
        g.drawString("우클릭: 추가   E: 편집   Del: 삭제", px + 8, ty); ty += 10

        g.color = Color(50, 40, 72); g.drawLine(px + 8, ty - 2, px + pw - 8, ty - 2); ty += 10
        if (selDec == null) {
            g.color = Color(80, 70, 110); g.font = toolFont
            g.drawString("장식을 선택하세요", px + 8, ty); ty += 18

            g.color = Color(60, 55, 90)
            g.drawString("하단 타임라인에서 클릭", px + 8, ty)
        } else {
            g.color = Color(195, 145, 255); g.font = headerFont
            g.drawString(selDec.id.ifEmpty { "(unnamed)" }, px + 8, ty); ty += 22

            g.font = toolFont
            fun propRow(label: String, value: String) {
                g.color = Color(130, 105, 185); g.drawString(label, px + 8, ty)
                g.color = Color(225, 225, 245); g.drawString(value, px + 100, ty); ty += 18
            }
            propRow("image",    selDec.image.ifEmpty { "(없음)" })
            propRow("timeMs",   "${selDec.timeMs} ms")

            propRow("durMs",    "${selDec.durationMs} ms")
            propRow("x / y",    "%.3f / %.3f".format(selDec.x, selDec.y))

            propRow("w / h",    "${selDec.width} / ${selDec.height}")
            propRow("opacity",  "%.2f".format(selDec.opacity))

            propRow("rotation", "%.1f°".format(selDec.rotation))
            propRow("depth",    "${selDec.depth}")

            propRow("effects",  "${selDec.effects.size}개")

            ty += 6
            g.color = Color(70, 58, 100); g.font = hintFont
            g.drawString("E: 편집   Del: 삭제", px + 8, ty)
        }
        ty += 10
        g.color = Color(50, 40, 72); g.drawLine(px + 8, ty - 2, px + pw - 8, ty - 2); ty += 8
        g.color = Color(80, 65, 115); g.font = hintFont
        g.drawString("장식 ${list.size}개", px + 8, ty)
    }

    // ── 데코레이션 마우스 핸들러 ─────────────────────────────────────────────────
    private fun handleDecorMouseClick(mx: Int, my: Int, button: Int, mods: Int) {
        // 하단 타임라인 영역만 처리
        if (my < decorTlY || my > decorTlY + decorTlH) return

        val list      = decorations
        val currentMs = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
        val cursorPx  = decorTlW / 2   // w/2 = 플레이헤드
        val laneH     = ((decorTlH - 20) / 5).coerceAtLeast(8)

        fun findHit(): Int {
            list.forEachIndexed { idx, dec ->
                val lane    = idx % 5
                val by      = decorTlY + 18 + lane * laneH
                val startPx = ((dec.timeMs - timelineScrollMs).toDouble() / visibleWindowMs * decorTlW).toInt()
                val endPx   = ((dec.timeMs + dec.durationMs - timelineScrollMs).toDouble() / visibleWindowMs * decorTlW).toInt()
                if (mx in startPx.coerceAtLeast(0)..endPx.coerceAtMost(decorTlW) &&
                    my in by..(by + laneH - 3)) return idx
            }
            return -1
        }

        when (button) {
            Keys.MOUSE_LEFT -> selectedDecorIdx = findHit()
            Keys.MOUSE_RIGHT -> {
                val clickMs = currentMs + (mx - cursorPx).toLong() * visibleWindowMs / decorTlW
                val hit     = findHit()
                val popup   = javax.swing.JPopupMenu()
                if (hit >= 0) {
                    popup.add(javax.swing.JMenuItem("편집…")).addActionListener {
                        javax.swing.SwingUtilities.invokeLater { openDecorEditDialog(hit) }
                    }
                    popup.add(javax.swing.JMenuItem("삭제")).addActionListener {
                        val cur = decorations
                        cur.removeAt(hit)
                        decorData = DecorationData(cur, decorData.screenEffects)
                        syncDecorRenderer()
                        if (selectedDecorIdx >= cur.size) selectedDecorIdx = cur.size - 1
                    }
                } else {
                    popup.add(javax.swing.JMenuItem("여기에 장식 추가 (${clickMs}ms)")).addActionListener {
                        javax.swing.SwingUtilities.invokeLater { openNewDecorDialog(clickMs.coerceAtLeast(0L)) }
                    }
                }
                SwingHelper.showPopup(popup, ctx.windowManager.glfwWindow)
            }
        }
    }

    private fun openDecorEditDialog(idx: Int) {
        val dec = decorations.getOrNull(idx) ?: return
        io.github.jwyoon1220.app.ui.DecorEditDialog(dec, songEntry.songDir).showAndGet()?.let { updated ->
            val cur = decorations
            cur[idx] = updated
            decorData = DecorationData(cur, decorData.screenEffects)
            syncDecorRenderer()
        }
    }

    private fun openNewDecorDialog(timeMs: Long) {
        val blank = Decoration(id = "dec_${timeMs}", timeMs = timeMs, durationMs = 1000L)
        io.github.jwyoon1220.app.ui.DecorEditDialog(blank, songEntry.songDir).showAndGet()?.let { newDec ->
            val cur = decorations
            cur.add(newDec)
            cur.sortBy { it.timeMs }
            decorData = DecorationData(cur, decorData.screenEffects)
            syncDecorRenderer()
            selectedDecorIdx = cur.indexOfFirst { it.id == newDec.id }
        }
    }

    /**
     * CustomGLRenderable — NanoVG 프레임 이후 실행됩니다.
     * CUSTOM 모드에서:
     *   1. 비디오 프레임을 에디터 뷰포트 위치에 GL 텍스처로 직접 렌더
     *   2. 타임라인 노트 아이템을 GlQuadBatchRenderer로 렌더 (NanoVG보다 빠름)
     *
     * 비디오는 NanoVG 배경(검은 사각형)이 있는 자리에 그려지므로 UI가 위로 올라오는
     * 레이어 순서: NanoVG(검은 배경, UI 패널, 텍스트) → GL(비디오, 노트 아이템)
     */
    override fun renderCustomGl(renderer: GlQuadBatchRenderer) {
        if (!useCustomGlRenderer) return

        // ── 1. 비디오 프레임 ─────────────────────────────────────────────────
        val glTex = ctx.videoBackground.getGlTextureId()
        if (glTex > 0 && ctx.videoBackground.getCurrentFrame() != null) {
            val vpH = renderH - HDR_H - btmH
            val vpW = vpH * 16 / 9
            val vpX = (mainW - vpW) / 2
            renderer.drawTexturedRect(
                x = vpX.toFloat(),
                y = HDR_H.toFloat(),
                w = vpW.toFloat(),
                h = vpH.toFloat(),
                textureId = glTex
            )
        }

        // ── 2. 타임라인 노트 아이템 ──────────────────────────────────────────
        val tlH   = NOTE_TL_H
        val laneH = tlH / 4
        val noteY = noteTrackY
        val viewMs = visibleWindowMs.toDouble()
        val w = mainW

        val notes = synchronized(notesLock) { mutableChart.notes.toList() }
        for ((idx, note) in notes.withIndex()) {
            val nx = ((note.time - timelineScrollMs).toDouble() / viewMs * w).toFloat()
            if (nx < -20f || nx > w + 20f) continue

            val isSelected = idx in selectedIndices
            val laneIdx    = note.lane % 4
            val ly         = (noteY + note.lane * laneH).toFloat()

            if (note.type == NoteType.SHORT) {
                // 단노트: 사전 캐시된 하이라이트 색상(밝게) 한 번만 그림
                val drawColor = if (isSelected) TL_SELECTED_COLOR else TL_BRIGHT_COLORS[laneIdx]
                renderer.drawRect(nx - 4f, ly + 4f, 8f, (laneH - 8).toFloat(), drawColor)
            } else {
                val endMs     = note.endTime ?: note.time
                val ex        = ((endMs - timelineScrollMs).toDouble() / viewMs * w).toFloat()
                val left      = minOf(nx, ex)
                val bw        = kotlin.math.abs(ex - nx).coerceAtLeast(4f)
                // 롱노트 바디: 사전 캐시된 bodyColor (per-lane × selected 조합)
                val bodyColor = if (isSelected) TL_SELECTED_BODY_COLOR
                                else TL_LONG_BODY_COLORS[laneIdx][0]
                val headColor = if (isSelected) TL_SELECTED_COLOR else TL_LANE_COLORS[laneIdx]
                renderer.drawRect(left, ly + laneH / 3f, bw, laneH / 3f, bodyColor)
                renderer.drawRect(nx - 4f, ly + 4f, 8f, (laneH - 8).toFloat(), headColor)
                renderer.drawRect(ex - 4f, ly + 4f, 8f, (laneH - 8).toFloat(), headColor)
            }
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
