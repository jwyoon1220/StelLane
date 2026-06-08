package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.DecorationRenderer
import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.ui.ingame.*
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
import io.github.jwyoon1220.core.data.DecEffect
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.LaneEventType
import io.github.jwyoon1220.engine.CustomGLRenderable
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.GlEffectProvider
import io.github.jwyoon1220.engine.GlScreenEffectData
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ImGuiRenderable
import imgui.ImGui
import imgui.type.ImFloat
import imgui.type.ImInt
import imgui.type.ImString
import imgui.flag.ImGuiWindowFlags
import it.unimi.dsi.fastutil.ints.IntArraySet
import java.awt.BasicStroke
import java.awt.Color
import java.io.File
import kotlin.math.abs

class EditorScene(
    private val ctx: GameContext,
    val songEntry: SongEntry,
    private val chartFile: File,
    chart: Chart
) : Scene(), CustomGLRenderable, ImGuiRenderable, GlEffectProvider {
    override val rendersBackground = true
    // 에디터는 마딩으로 NanoVG 패스로 비디오를 그림 (커스텀 GL 패스 불필요)
    override val useCustomGlRenderer: Boolean = false

    override fun collectActiveGlEffects(): List<GlScreenEffectData> =
        decorRenderer?.collectGlEffects(cachedCurrentTimeMs) ?: emptyList()

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
    // 통합 에디터 레이아웃 상수 (ImGui에서 갱신됨)
    private var timelineX  = 0
    private var timelineW  = 1280
    private var unifiedTlY = 0
    private var NOTE_TL_H  = 90
    private var DECOR_TL_H = 110
    private val noteTrackY get() = unifiedTlY + DECOR_TL_H
    private val mainW      get() = timelineW

    // 하위 호환 (findNoteAt, mouseDragged 등에서 사용)
    private val tlY       get() = noteTrackY
    private val tlW       get() = timelineW
    private val tlH       get() = NOTE_TL_H
    private val tlCursorX get() = timelineW / 2

    @Volatile private var isViewportHovered = false
    @Volatile private var isTimelineHovered = false

    private fun shouldIgnoreMouse(): Boolean {
        // ImGui가 마우스를 점유 중이면 게임 로직(노트 클릭 등) 무시
        return ImGui.getIO().wantCaptureMouse
    }

    // ── 레코딩 (홀드 추적) ────────────────────────────────────────────────────
    private val heldLaneStartMs  = LongArray(4) { -1L }
    // GameLoopThread(update) ↔ EDT(render) 동시 접근 목적: mutableChart.notes 보호
    private val notesLock = Any()

    // ── UI 상태 ──────────────────────────────────────────────────────────────
    private var contextMenuX = 0f
    private var contextMenuY = 0f
    private var contextMenuNoteIdx = -1
    private var contextMenuDecorIdx = -1
    private var contextMenuClickMs = 0L
    private var contextMenuLane = 0
    // ImGui.openPopup()은 반드시 ImGui 프레임 내(renderImGui)에서 호출해야 합니다.
    // 입력 콜백에서 직접 호출하면 JVM 네이티브 크래시가 발생하므로 플래그로 지연합니다.
    @Volatile private var pendingContextMenu = false

    private var editingDecorIdx = -1
    private var editingDecor: Decoration? = null
    private var imageBrowserOpen = false
    private var imageBrowserDir: File = songEntry.songDir
    private var imageBrowserSelectedFile: File? = null
    private var imageBrowserEntries: List<ImageBrowserEntry> = emptyList()
    
    // ImGui용 바인딩
    private val imId = ImString(128)
    private val imImage = ImString(256)
    private val imTime = ImInt(0)
    private val imDuration = ImInt(0)
    private val imX = ImFloat(0f)
    private val imY = ImFloat(0f)
    private val imW = ImFloat(0f)
    private val imH = ImFloat(0f)
    private val imOpacity = ImFloat(1f)
    private val imRotation = ImFloat(0f)
    private val imDepth = ImInt(0)
    private var showShortcutPanel = true
    private var showDecorPanel    = true
    private var sidebarW          = 280
    private var headerH           = 46


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
    // ── 뷰포트 크기 조절 ─────────────────────────────────────────────────────
    private var vpHeightPct = 100   // 사용 가능한 높이의 %
    // 캐시된 뷰포트 좌표 (render→mouse/renderCustomGl 공유, 1프레임 지연 무해)
    private var cachedVpH = 0
    private var cachedVpW = 0
    private var cachedVpX = 0
    private var cachedVpY = 46
    private var overviewBarY = 0
    private var overviewBarH = 18
    private var overviewBarGap = 8
    private var overviewDragActive = false


    // ── 폰트 ─────────────────────────────────────────────────────────────────
    private val headerFont = FontLoader.semiBold(20f)
    private val infoFont   = FontLoader.regular(15f)
    private val hintFont   = FontLoader.light(12f)
    private val toolFont   = FontLoader.regular(13f)

    // ── 컴패니언 — 매 프레임 Color 할당 방지 ─────────────────────────────────────
    companion object {
        private val IMAGE_FILE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

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

    private data class ImageBrowserEntry(
        val file: File,
        val displayName: String,
        val isDirectory: Boolean
    )

    // ── GameState ─────────────────────────────────────────────────────────────

    override fun enter() {
        super.enter()
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

    private fun resolveMediaPath(): String? {
        val song = songEntry.song
        return when {
            song.videoPath != null -> File(songEntry.songDir, song.videoPath).absolutePath
            song.audioPath != null -> File(songEntry.songDir, song.audioPath).absolutePath
            else                   -> null
        }
    }

    override fun exit() {
        ctx.videoBackground.onPlayingStarted = null
        ctx.videoBackground.stop()
        ctx.inputManager.clearEvents()
        super.exit()
    }

    // ── 캐시된 현재 시간 (프레임당 1회 계산으로 제한)
    private var cachedCurrentTimeMs = 0L
    private var lastCacheFrameId = -1L
    private var frameCounter = 0L

    // statusStr caching fields
    private var cachedTimeMs = -1L
    private var cachedTimeStr = ""
    private var lastIsPlaying = false
    private var lastDecorMode = false
    private var lastRecordingMode = false
    private var lastQuantizeEnabled = false
    private var lastQuantizeDivision = 0
    private var lastVisibleWindowMs = 0L
    private var cachedStatusStr = ""

    override fun update(deltaTime: Double) {
        frameCounter++
        val events = ctx.inputManager.pollEvents()
        
        if (pendingSeekMs >= 0L) {
            val now = System.currentTimeMillis()
            if (now - lastSeekTimeMs > 66) { // ~15 FPS 제한으로 버벅임 방지
                ctx.videoBackground.seek(pendingSeekMs)
                lastSeekTimeMs = now
                pendingSeekMs = -1L
            }
        }
        
        // getSmoothTimeMs() 호출을 프레임당 1회로 제한 (이전 호출과 동일하면 캐시된 값 사용)
        val frameId = ctx.videoBackground.getFrameId()
        if (frameId != lastCacheFrameId || lastCacheFrameId == -1L) {
            cachedCurrentTimeMs = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
            lastCacheFrameId = frameId
        }
        val currentTimeMs = cachedCurrentTimeMs

        // audioFade 효과 적용 (재생 중에만)
        if (isPlaying) {
            decorRenderer?.computeTargetVolumePercent(currentTimeMs)?.let {
                ctx.videoBackground.setTargetVolumePercent(it)
            }
        }

        // 재생 중일 때 타임라인 스크롤 자동 전진 (페이지 넘김 방식 또는 부드러운 스크롤)
        if (isPlaying) {
            if (currentTimeMs > timelineScrollMs + visibleWindowMs * 0.9) {
                timelineScrollMs = currentTimeMs - (visibleWindowMs * 0.1).toLong()
            } else if (currentTimeMs < timelineScrollMs) {
                timelineScrollMs = currentTimeMs - (visibleWindowMs * 0.1).toLong()
            }
        }
        
        if (recordingMode && isPlaying) {
            for (event in events) {
                val lane = event.lane
                when (event.type) {
                    LaneEventType.PRESS   -> heldLaneStartMs[lane] = currentTimeMs
                    LaneEventType.RELEASE -> {
                        val startMs = heldLaneStartMs[lane]
                        if (startMs < 0) continue
                        val duration     = currentTimeMs - startMs
                        val snappedStart = snapTime(startMs)

                        saveSnapshot()
                        synchronized(notesLock) {
                            if (duration < 150 || noteMode == NoteMode.NORMAL) {
                                mutableChart.notes.add(MutableNote(snappedStart, lane, NoteType.SHORT))
                            } else {
                                mutableChart.notes.add(MutableNote(snappedStart, lane, NoteType.LONG, snapTime(currentTimeMs)))
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

        // 캐시된 현재 시간 사용 (update()에서 계산됨)
        val currentTimeMs = cachedCurrentTimeMs
        val pos     = maxOf(currentTimeMs, 0L)
        val timeChanged = (pos != cachedTimeMs)
        val timeStr: String
        if (!timeChanged) {
            timeStr = cachedTimeStr
        } else {
            val min = pos / 60_000
            val sec = (pos % 60_000) / 1000
            val ms = pos % 1000
            val secStr = if (sec < 10) "0$sec" else "$sec"
            val msStr = if (ms < 10) "00$ms" else if (ms < 100) "0$ms" else "$ms"
            timeStr = "$min:$secStr.$msStr"
            cachedTimeMs = pos
            cachedTimeStr = timeStr
        }

        // ── 레이아웃 계산 ──────────────────────────────────────────────────────
        val topH = headerH
        val leftPanelW = if (showDecorPanel) sidebarW else 0
        val rightPanelW = if (showShortcutPanel) sidebarW else 0
        val timelineH = if (decorMode) 238 else 138
        val contentTimelineH = timelineH - overviewBarH - overviewBarGap
        
        timelineX = leftPanelW
        timelineW = w - leftPanelW - rightPanelW
        unifiedTlY = h - timelineH
        overviewBarY = h - overviewBarH
        
        NOTE_TL_H  = if (decorMode) contentTimelineH - 110 else contentTimelineH - 20
        DECOR_TL_H = if (decorMode) 110 else 0
        
        decorTlX = timelineX; decorTlY = unifiedTlY; decorTlW = timelineW; decorTlH = DECOR_TL_H
        timelineScrollMs = clampTimelineScroll(timelineScrollMs)

        // 뷰포트 영역 계산
        val vpAreaW = timelineW
        val vpAreaH = h - topH - timelineH
        
        val targetH = (vpAreaH * vpHeightPct / 100.0).toInt()
        val targetW = (targetH * 16 / 9).coerceAtMost(vpAreaW)
        val finalH = (targetW * 9 / 16).toInt()

        cachedVpH = finalH
        cachedVpW = targetW
        cachedVpX = timelineX + (vpAreaW - targetW) / 2
        cachedVpY = topH + (vpAreaH - finalH) / 2

        // ── 0. 전체 배경 ──────────────────────────────────────────────────────
        g.color = Color(8, 6, 16)
        g.fillRect(0, 0, w, h)

        // ── 1. 비디오 뷰포트 (가장 먼저 그림) ────────────────────────────────
        if (cachedVpW > 0 && cachedVpH > 0) {
            // 뷰포트 외곽 어두운 테두리
            g.color = Color(0, 0, 0, 180)
            g.fillRect(cachedVpX - 2, cachedVpY - 2, cachedVpW + 4, cachedVpH + 4)

            if (!useCustomGlRenderer) {
                val nvgHandle = ctx.videoBackground.getNvgImageHandle(g.vg)
                if (nvgHandle >= 0) {
                    g.drawNvgImage(nvgHandle, cachedVpX.toFloat(), cachedVpY.toFloat(), cachedVpW.toFloat(), cachedVpH.toFloat())
                } else {
                    val frame = ctx.videoBackground.getCurrentFrame()
                    if (frame != null) {
                        g.drawImage(frame, cachedVpX, cachedVpY, cachedVpW, cachedVpH, null)
                    } else {
                        // 영상 없을 때 자리표시자
                        g.color = Color(20, 15, 35)
                        g.fillRect(cachedVpX, cachedVpY, cachedVpW, cachedVpH)
                        g.color = Color(60, 50, 90)
                        g.font = infoFont
                        g.drawStringCentered("▶ 영상 없음", (cachedVpX + cachedVpW / 2).toFloat(), (cachedVpY + cachedVpH / 2).toFloat())
                    }
                }
            }

            // ── 1b. 장식 오버레이 (영상 위에 그림) ──────────────────────────
            val decorRend = decorRenderer
            if (decorRend != null) {
                g.save()
                g.setClip(cachedVpX, cachedVpY, cachedVpW, cachedVpH)
                g.translate(cachedVpX.toDouble(), cachedVpY.toDouble())
                g.scale(cachedVpW / 1280.0, cachedVpH / 720.0)
                decorRend.render(g, currentTimeMs, beforeNotes = true)
                decorRend.render(g, currentTimeMs, beforeNotes = false)
                decorRend.renderScreenEffects(g, currentTimeMs)
                g.restore()
            }

            // ── 1c. 선택된 장식 바운딩 박스 가이드 ──────────────────────────
            if (decorMode && selectedDecorIdx >= 0) {
                val dec = decorations.getOrNull(selectedDecorIdx)
                dec?.let { d ->
                    val scaleX = cachedVpW / 1280f; val scaleY = cachedVpH / 720f
                    val dw = if (d.width <= 1f) d.width * 1280f else d.width
                    val dh = if (d.height <= 1f) d.height * 720f else d.height
                    val fx = cachedVpX + ((d.x * 1280f - d.pivotX * dw) * scaleX).toInt()
                    val fy = cachedVpY + ((d.y * 720f  - d.pivotY * dh) * scaleY).toInt()
                    val fw = (dw * scaleX).toInt().coerceAtLeast(2)
                    val fh = (dh * scaleY).toInt().coerceAtLeast(2)

                    g.color = Color(220, 160, 255, 200)
                    g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f)
                    g.drawRect(fx, fy, fw, fh)
                    g.stroke = BasicStroke(1.5f)
                    // 코너 핸들
                    for ((hx, hy) in listOf(fx to fy, fx + fw to fy, fx to fy + fh, fx + fw to fy + fh)) {
                        g.color = Color(220, 160, 255); g.fillRect(hx - 4, hy - 4, 8, 8)
                        g.color = Color(0, 0, 0, 120); g.drawRect(hx - 4, hy - 4, 8, 8)
                    }
                    // 이름 라벨
                    g.color = Color(220, 160, 255, 180); g.font = hintFont
                    g.drawString(d.id.ifEmpty { "(decoration)" }, (fx + 2).toFloat(), (fy - 4).toFloat())
                }
            }
        }

        // ── 2. 사이드 패널 ────────────────────────────────────────────────────
        if (showDecorPanel) {
            g.fillLinearGradient(
                0f, topH.toFloat(), leftPanelW.toFloat(), (h - topH).toFloat(),
                0f, 0f, leftPanelW.toFloat(), 0f,
                Color(18, 13, 32, 230), Color(12, 8, 22, 190)
            )
            g.color = Color(80, 55, 130, 80); g.drawLine(leftPanelW, topH, leftPanelW, h)
            renderDecorPropsPanel(g, 0, topH, leftPanelW, h - topH)
        }
        if (showShortcutPanel) {
            val dx = w - rightPanelW
            g.fillLinearGradient(
                dx.toFloat(), topH.toFloat(), rightPanelW.toFloat(), (h - topH).toFloat(),
                dx.toFloat(), 0f, w.toFloat(), 0f,
                Color(12, 8, 22, 190), Color(18, 13, 32, 230)
            )
            g.color = Color(80, 55, 130, 80); g.drawLine(dx, topH, dx, h)
            renderNoteShortcutPanel(g, dx, topH, rightPanelW, h - topH)
        }

        // ── 3. 상단 헤더 ──────────────────────────────────────────────────────
        g.fillLinearGradient(
            0f, 0f, w.toFloat(), topH.toFloat(),
            0f, 0f, 0f, topH.toFloat(),
            Color(40, 22, 80, 245), Color(22, 14, 45, 245)
        )
        g.color = Color(120, 70, 220, 100); g.drawLine(0, topH - 1, w, topH - 1)
        // 왼쪽: 곡 제목
        g.font = headerFont; g.color = Color(190, 150, 255)
        g.drawString("✏  ${songEntry.song.title}", 16f, 30f)
        // 오른쪽: 상태 + 버전
        g.font = infoFont
        val statusNeedsUpdate = timeChanged ||
               isPlaying != lastIsPlaying ||
               decorMode != lastDecorMode ||
               recordingMode != lastRecordingMode ||
               quantizeEnabled != lastQuantizeEnabled ||
               quantizeDivision != lastQuantizeDivision ||
               visibleWindowMs != lastVisibleWindowMs

        val statusStr = if (statusNeedsUpdate) {
            val playIcon = if (isPlaying) "▶" else "⏸"
            val modeStr = if (decorMode) "田 DECOR" else "♩ NOTE"
            val recStr = if (recordingMode) "● REC" else ""
            val quantStr = if (quantizeEnabled) "1/$quantizeDivision" else "Free"
            val winSec = visibleWindowMs / 1000
            val res = "B1.2.2  ·  $timeStr  $playIcon  $modeStr  $recStr  $quantStr  ${winSec}s"

            lastIsPlaying = isPlaying
            lastDecorMode = decorMode
            lastRecordingMode = recordingMode
            lastQuantizeEnabled = quantizeEnabled
            lastQuantizeDivision = quantizeDivision
            lastVisibleWindowMs = visibleWindowMs
            cachedStatusStr = res
            res
        } else {
            cachedStatusStr
        }
        g.color = Color(140, 125, 185); g.drawStringRight(statusStr, (w - 12).toFloat(), 30f)
        if (unsaved) { g.color = Color(255, 90, 90); g.fillOval(w - 8, 10, 5, 5) }

        // ── 4. 타임라인 ──────────────────────────────────────────────────────
        // 객체 할당 최소화: 스냅샷 대신 직접 참조 (읽기 전용, notesLock 보호)
        val noteY = noteTrackY
        synchronized(notesLock) {
            Timeline.render(g, mutableChart, timelineScrollMs, currentTimeMs, timelineX, noteY, timelineW, NOTE_TL_H, visibleWindowMs, selectedIndices, songEntry.song.bpm?.toDouble(), drawNotes = !useCustomGlRenderer)
        }
        
        if (decorMode) {
            // 장식 그리드 & 아이템
            val stepMs = (visibleWindowMs / 8).coerceAtLeast(100L)
            val gridStart = (timelineScrollMs / stepMs) * stepMs
            for (i in 0..16) {
                val t = gridStart + i * stepMs
                val px = timelineX + ((t - timelineScrollMs).toDouble() / visibleWindowMs * timelineW).toInt()
                if (px in timelineX..(timelineX + timelineW)) {
                    g.color = Color(40, 35, 60, 120); g.drawLine(px, unifiedTlY, px, unifiedTlY + DECOR_TL_H)
                }
            }
            val laneH = (DECOR_TL_H - 20) / 5
            decorations.forEachIndexed { idx, dec ->
                val lane = idx % 5; val by = unifiedTlY + 18 + lane * laneH
                val startPx = timelineX + ((dec.timeMs - timelineScrollMs).toDouble() / visibleWindowMs * timelineW).toInt()
                val endPx = timelineX + ((dec.timeMs + dec.durationMs - timelineScrollMs).toDouble() / visibleWindowMs * timelineW).toInt()
                
                // 완전히 화면 밖에 있는 경우 렌더링 스킵
                if (endPx <= timelineX || startPx >= timelineX + timelineW) return@forEachIndexed
                
                val bx = startPx.coerceAtLeast(timelineX)
                val maxW = timelineW - (bx - timelineX)
                if (maxW >= 4) {
                    val bw2 = (endPx - bx).coerceIn(4, maxW)
                    val hue = (idx * 53) % 360; val bright = if (idx == selectedDecorIdx) 1.0f else 0.6f
                    g.color = Color.getHSBColor(hue / 360f, 0.7f, bright)
                    g.fillRoundRect(bx, by, bw2, laneH - 3, 4, 4)
                    if (idx == selectedDecorIdx) { g.color = Color.WHITE; g.drawRoundRect(bx, by, bw2, laneH - 3, 4, 4) }
                }
            }
        }
        
        // 플레이헤드
        val phX = timelineX + ((currentTimeMs - timelineScrollMs).toDouble() / visibleWindowMs * timelineW).toInt()
        if (phX in timelineX..(timelineX + timelineW)) {
            g.color = Color(220, 100, 255); g.drawLine(phX, unifiedTlY, phX, overviewBarY - 2)
            g.fillPolygon(intArrayOf(phX - 6, phX + 6, phX), intArrayOf(unifiedTlY, unifiedTlY, unifiedTlY + 10), 3)
        }

        renderOverviewBar(g, currentTimeMs)

        // 8. Tooltips & Guidelines (NanoVG)
        // renderGuidelines(g)
        // renderTooltips(g)
    }

    override fun keyPressed(key: Int, mods: Int) {
        val ctrl  = Keys.isCtrl(mods)
        val shift = Keys.isShift(mods)

        if (decorMode) {
            when {
                ctrl && shift && key == Keys.D -> toggleDecorMode()
                ctrl && key == Keys.S          -> saveDecor()
                key == Keys.E && selectedDecorIdx >= 0 -> openDecorEditDialog(selectedDecorIdx)
                key == Keys.DELETE || key == Keys.BACKSPACE -> {
                    val idx = selectedDecorIdx
                    if (idx >= 0) {
                        decorations.removeAt(idx)
                        decorData = DecorationData(decorations, decorData.screenEffects)
                        selectedDecorIdx = (idx - 1).coerceAtLeast(-1)
                    }
                }
                key == Keys.ESCAPE -> decorMode = false
                key == Keys.SPACE  -> togglePlay()
                key == Keys.LEFT   -> seek(-1_000L)
                key == Keys.RIGHT  -> seek(1_000L)
            }
            return
        }

        when {
            ctrl && shift && key == Keys.D -> toggleDecorMode()
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
            ctrl && key == Keys.A                   -> selectAll()
            ctrl && key == Keys.D                   -> selectedIndices.clear()
            key == Keys.TAB && !shift               -> navigateNote(+1)
            key == Keys.TAB && shift                -> navigateNote(-1)
            ctrl && key == Keys.Z && !shift         -> undo()
            ctrl && key == Keys.Y                   -> redo()
            ctrl && key == Keys.Z && shift          -> redo()
            ctrl && key == Keys.C                   -> copy()
            ctrl && key == Keys.X                   -> cut()
            ctrl && key == Keys.V                   -> paste()
            key == Keys.DELETE || key == Keys.BACKSPACE -> deleteSelected()
            key == Keys.N                           -> cycleNoteMode()
            key == Keys.R                           -> { recordingMode = !recordingMode; heldLaneStartMs.fill(-1L) }
            key == Keys.Q                           -> cycleQuantize()
            key == Keys.N4                          -> { quantizeEnabled = true; quantizeDivision = 4  }
            key == Keys.N8                          -> { quantizeEnabled = true; quantizeDivision = 8  }
            key == Keys.N6                          -> { quantizeEnabled = true; quantizeDivision = 16 }
            key == Keys.EQUAL || key == Keys.PLUS   -> zoomIn()
            key == Keys.MINUS                       -> zoomOut()
            ctrl && key == Keys.S                   -> if (decorMode) saveDecor() else save()
            ctrl && key == Keys.COMMA               -> openSettings()
            ctrl && shift && key == Keys.O          -> openCalibration()
            key == Keys.ESCAPE                      -> {
                if (selectedIndices.isNotEmpty()) selectedIndices.clear()
                else ctx.stateManager.changeState(io.github.jwyoon1220.app.ecs.SongSelectScene(ctx, io.github.jwyoon1220.app.ecs.SelectMode.EDIT))
            }
        }
    }

    override fun keyTyped(codepoint: Int) {}

    private fun togglePlay() { if (isPlaying) pause() else startPlay() }

    private fun startPlay() {
        if (!isPlaying) {
            val path = resolveMediaPath() ?: return
            if (ctx.videoBackground.isPlayable()) {
                val currentMs = ctx.videoBackground.getTimeMs()
                val lengthMs = ctx.videoBackground.getLengthMs()
                if (lengthMs > 0L && currentMs >= lengthMs - 100) ctx.videoBackground.play(path)
                else ctx.videoBackground.resume()
            } else ctx.videoBackground.play(path)
            isPlaying = true
        }
    }

    private fun pause() {
        if (isPlaying) { ctx.videoBackground.pause(); isPlaying = false; recordingMode = false; heldLaneStartMs.fill(-1L) }
    }

    private fun seek(deltaMs: Long) {
        val cur = ctx.videoBackground.getSmoothTimeMs()
        ctx.videoBackground.seek(maxOf(0L, cur + deltaMs))
    }

    private fun seekToEnd() {
        val lastNote = mutableChart.notes.maxOfOrNull { it.endTime ?: it.time } ?: return
        ctx.videoBackground.seek(lastNote + mutableChart.offsetMs + 2_000L)
    }

    private fun selectAll() {
        selectedIndices.clear(); selectedIndices.addAll(mutableChart.notes.indices); cursorNoteIdx = mutableChart.notes.lastIndex
    }

    private fun navigateNote(direction: Int) {
        if (mutableChart.notes.isEmpty()) return
        cursorNoteIdx = (cursorNoteIdx + direction).coerceIn(0, mutableChart.notes.lastIndex)
        selectedIndices.clear(); selectedIndices.add(cursorNoteIdx)
        val noteTime = mutableChart.notes[cursorNoteIdx].time + mutableChart.offsetMs
        ctx.videoBackground.seek(noteTime)
    }

    private fun saveSnapshot() {
        undoStack.addLast(mutableChart.notes.map { it.copy() })
        redoStack.clear()
        if (undoStack.size > 50) undoStack.removeFirst()
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(mutableChart.notes.map { it.copy() })
        mutableChart.notes.clear(); mutableChart.notes.addAll(undoStack.removeLast())
        selectedIndices.clear(); unsaved = true
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(mutableChart.notes.map { it.copy() })
        mutableChart.notes.clear(); mutableChart.notes.addAll(redoStack.removeLast())
        selectedIndices.clear(); unsaved = true
    }

    private fun copy() {
        if (selectedIndices.isEmpty()) return
        val sorted = selectedIndices.sorted().map { mutableChart.notes[it] }
        clipboardBaseMs = sorted.minOf { it.time }
        clipboard = sorted.map { it.copy() }
    }

    private fun cut() { copy(); deleteSelected() }

    private fun paste() {
        if (clipboard.isEmpty()) return
        val currentMs = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
        val delta     = currentMs - clipboardBaseMs
        saveSnapshot()
        val pasted = clipboard.map { it.copy(time = snapTime(it.time + delta), endTime = it.endTime?.let { e -> snapTime(e + delta) }) }
        mutableChart.notes.addAll(pasted); mutableChart.notes.sortBy { it.time }
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
        selectedIndices.clear(); cursorNoteIdx = -1; unsaved = true
    }

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

    private fun zoomIn() { if (zoomIdx > 0) { zoomIdx--; visibleWindowMs = zoomLevels[zoomIdx]; timelineScrollMs = clampTimelineScroll(timelineScrollMs) } }
    private fun zoomOut() { if (zoomIdx < zoomLevels.lastIndex) { zoomIdx++; visibleWindowMs = zoomLevels[zoomIdx]; timelineScrollMs = clampTimelineScroll(timelineScrollMs) } }

    private fun getTimelineOverviewRangeMs(): Long {
        val chartEndMs = mutableChart.notes.maxOfOrNull { it.endTime ?: it.time } ?: 0L
        val mediaEndMs = (ctx.videoBackground.getLengthMs() - mutableChart.offsetMs).coerceAtLeast(0L)
        return maxOf(visibleWindowMs, chartEndMs + 2_000L, mediaEndMs)
    }

    private fun getTimelineMaxScrollMs(): Long = (getTimelineOverviewRangeMs() - visibleWindowMs).coerceAtLeast(0L)

    private fun clampTimelineScroll(value: Long): Long = value.coerceIn(0L, getTimelineMaxScrollMs())

    private fun updateTimelineOverviewFromMouse(mouseX: Int) {
        if (timelineW <= 0) return
        val ratio = ((mouseX - timelineX).toDouble() / timelineW).coerceIn(0.0, 1.0)
        timelineScrollMs = (ratio * getTimelineMaxScrollMs()).toLong()
    }

    private fun renderOverviewBar(g: DrawContext, currentTimeMs: Long) {
        if (timelineW <= 0) return
        val barY = overviewBarY
        val totalRangeMs = getTimelineOverviewRangeMs()
        val maxScrollMs = getTimelineMaxScrollMs()
        val selectionRatio = (visibleWindowMs.toDouble() / totalRangeMs).coerceIn(0.05, 1.0)
        val selectionW = (timelineW * selectionRatio).toInt().coerceIn(minOf(24, timelineW), timelineW)
        val selectionX = if (maxScrollMs == 0L) {
            timelineX
        } else {
            timelineX + ((timelineW - selectionW) * (timelineScrollMs.toDouble() / maxScrollMs)).toInt()
        }
        val playheadRatio = (currentTimeMs.coerceIn(0L, totalRangeMs).toDouble() / totalRangeMs).coerceIn(0.0, 1.0)
        val playheadX = timelineX + (timelineW * playheadRatio).toInt().coerceIn(0, timelineW)

        g.color = Color(12, 10, 20, 235)
        g.fillRoundRect(timelineX, barY, timelineW, overviewBarH, 6, 6)
        g.color = Color(38, 32, 58, 180)
        g.drawRoundRect(timelineX, barY, timelineW, overviewBarH, 6, 6)

        synchronized(notesLock) {
            mutableChart.notes.forEach { note ->
                val noteRatio = (note.time.coerceIn(0L, totalRangeMs).toDouble() / totalRangeMs).coerceIn(0.0, 1.0)
                val noteX = timelineX + (timelineW * noteRatio).toInt()
                g.color = TL_LANE_COLORS[note.lane].let { Color(it.red, it.green, it.blue, 130) }
                g.drawLine(noteX, barY + 3, noteX, barY + overviewBarH - 3)
            }
        }

        g.color = Color(110, 72, 205, 180)
        g.fillRoundRect(selectionX, barY + 2, selectionW, overviewBarH - 4, 5, 5)
        g.color = Color(220, 200, 255, 220)
        g.drawRoundRect(selectionX, barY + 2, selectionW, overviewBarH - 4, 5, 5)

        g.color = Color(255, 140, 255, 220)
        g.drawLine(playheadX, barY + 1, playheadX, barY + overviewBarH - 1)
    }

    override fun mousePressed(x: Float, y: Float, button: Int, mods: Int) {
        val mx = x.toInt(); val my = y.toInt()
        timelineDragStartX = mx
        if (button == Keys.MOUSE_LEFT && my in overviewBarY..(overviewBarY + overviewBarH) && mx in timelineX..(timelineX + timelineW)) {
            overviewDragActive = true
            updateTimelineOverviewFromMouse(mx)
            return
        }
        if (decorMode) {
            val vpH = cachedVpH; val vpW = cachedVpW; val vpX = cachedVpX; val vpY = cachedVpY
            if (mx in vpX..(vpX+vpW) && my in vpY..(vpY+vpH)) {
                val scaleRatioX = vpW / 1280f; val scaleRatioY = vpH / 720f
                val selDec = decorations.getOrNull(selectedDecorIdx)
                if (selDec != null) {
                    val bw = if (selDec.width <= 1f) selDec.width * 1280f else selDec.width
                    val bh = if (selDec.height <= 1f) selDec.height * 720f else selDec.height
                    val fw = (bw * scaleRatioX).toInt().coerceAtLeast(1); val fh = (bh * scaleRatioY).toInt().coerceAtLeast(1)
                    val lx = vpX + ((selDec.x * 1280f - selDec.pivotX * bw) * scaleRatioX).toInt()
                    val lt = vpY + ((selDec.y * 720f  - selDec.pivotY * bh) * scaleRatioY).toInt()
                    val hs = 12
                    if (mx in (lx + fw - hs)..(lx + fw + hs) && my in (lt + fh - hs)..(lt + fh + hs)) {
                        resizingDecorIdx = selectedDecorIdx; decorResizePressX = mx; decorResizePressY = my
                        decorResizeOrigW = selDec.width; decorResizeOrigH = selDec.height; return
                    }
                    if (mx in lx..(lx+fw) && my in lt..(lt+fh)) {
                        draggingDecorIdx = selectedDecorIdx
                        decorDragOffsetX = selDec.x - (mx - vpX) / (1280f * scaleRatioX); decorDragOffsetY = selDec.y - (my - vpY) / (720f * scaleRatioY); return
                    }
                }
            }
        }
        if (my in unifiedTlY..(unifiedTlY + DECOR_TL_H + NOTE_TL_H) && mx in timelineX..(timelineX + timelineW)) {
            if (decorMode && my < unifiedTlY + DECOR_TL_H) {
                val hit = findDecorAt(mx, my)
                if (hit >= 0) {
                    timelineDecorDragIdx = hit; selectedDecorIdx = hit
                    val startPx = timelineX + ((decorations[hit].timeMs - timelineScrollMs).toDouble() / visibleWindowMs * timelineW).toInt()
                    timelineDecorDragOffsetX = (mx - startPx).toFloat(); return
                }
            } else if (my >= noteTrackY) {
                val laneH = NOTE_TL_H / 4; val hit = findNoteAt(mx, my, ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs, laneH)
                if (hit >= 0 && button == Keys.MOUSE_LEFT) { noteResizeIdx = hit; noteResizeMoved = false }
            }
        }
    }

    override fun mouseReleased(x: Float, y: Float, button: Int, mods: Int) {
        if (noteResizeIdx >= 0 && noteResizeMoved) { saveSnapshot(); unsaved = true }
        overviewDragActive = false
        noteResizeIdx = -1; noteResizeMoved = false; draggingDecorIdx = -1; resizingDecorIdx = -1; timelineDecorDragIdx = -1
        if (pendingSeekMs >= 0L) { ctx.videoBackground.seek(pendingSeekMs); pendingSeekMs = -1L }
    }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        val mx = x.toInt(); val my = y.toInt()
        if (button == Keys.MOUSE_LEFT && my in overviewBarY..(overviewBarY + overviewBarH) && mx in timelineX..(timelineX + timelineW)) {
            updateTimelineOverviewFromMouse(mx)
            return
        }
        if (my >= noteTrackY && my < overviewBarY - overviewBarGap && mx in timelineX..(timelineX + timelineW)) {
            val laneH = NOTE_TL_H / 4; val currentTimeMs = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
            val hit = findNoteAt(mx, my, currentTimeMs, laneH)
            if (button == Keys.MOUSE_LEFT) {
                if (hit >= 0) {
                    if (Keys.isShift(mods)) { if (hit in selectedIndices) selectedIndices.remove(hit) else selectedIndices.add(hit) }
                    else { selectedIndices.clear(); selectedIndices.add(hit); cursorNoteIdx = hit }
                } else {
                    selectedIndices.clear()
                    val clickMs = timelineScrollMs + (mx - timelineX).toLong() * visibleWindowMs / timelineW
                    ctx.videoBackground.seek(maxOf(0L, clickMs + mutableChart.offsetMs))
                }
            } else if (button == Keys.MOUSE_RIGHT) {
                contextMenuNoteIdx = hit; contextMenuDecorIdx = -1
                contextMenuClickMs = snapTime(timelineScrollMs + (mx - timelineX).toLong() * visibleWindowMs / timelineW)
                contextMenuLane = ((my - noteTrackY) / laneH).coerceIn(0, 3)
                pendingContextMenu = true
            }
        } else if (decorMode && my in unifiedTlY..(unifiedTlY + DECOR_TL_H) && mx in timelineX..(timelineX + timelineW)) {
            val hit = findDecorAt(mx, my)
            if (button == Keys.MOUSE_LEFT) selectedDecorIdx = hit
            else if (button == Keys.MOUSE_RIGHT) {
                contextMenuNoteIdx = -1; contextMenuDecorIdx = hit
                contextMenuClickMs = timelineScrollMs + (mx - timelineX).toLong() * visibleWindowMs / timelineW
                pendingContextMenu = true
            }
        }
    }

    private fun findDecorAt(mx: Int, my: Int): Int {
        val laneH = (DECOR_TL_H - 20) / 5
        decorations.forEachIndexed { idx, dec ->
            val lane = idx % 5; val by = unifiedTlY + 18 + lane * laneH
            val startPx = timelineX + ((dec.timeMs - timelineScrollMs).toDouble() / visibleWindowMs * timelineW).toInt()
            val endPx = timelineX + ((dec.timeMs + dec.durationMs - timelineScrollMs).toDouble() / visibleWindowMs * timelineW).toInt()
            if (mx in startPx.coerceAtLeast(timelineX)..endPx.coerceAtMost(timelineX+timelineW) && my in by..(by+laneH)) return idx
        }
        return -1
    }

    private fun findNoteAt(mx: Int, my: Int, currentTimeMs: Long, laneH: Int): Int {
        mutableChart.notes.forEachIndexed { idx, note ->
            val nx = timelineX + ((note.time - timelineScrollMs).toDouble() / visibleWindowMs * tlW).toInt()
            if (mx < nx - 6 || mx > nx + 6) return@forEachIndexed
            val ly = tlY + note.lane * laneH
            if (my < ly || my > ly + laneH) return@forEachIndexed
            return idx
        }
        return -1
    }

    override fun mouseDragged(x: Float, y: Float, button: Int) {
        if (timelineW <= 0 || cachedVpW <= 0 || cachedVpH <= 0) return
        val mx = x.toInt(); val my = y.toInt()
        if (overviewDragActive && button == Keys.MOUSE_LEFT) {
            updateTimelineOverviewFromMouse(mx)
            return
        }
        if (resizingDecorIdx >= 0 && decorMode) {
            val vpH = cachedVpH; val vpW = cachedVpW; val dx = (mx - decorResizePressX).toFloat(); val dy = (my - decorResizePressY).toFloat()
            val dec = decorations.getOrNull(resizingDecorIdx) ?: run { resizingDecorIdx = -1; return }
            val newW = if (decorResizeOrigW <= 1f) (decorResizeOrigW + dx / vpW).coerceAtLeast(0.02f) else (decorResizeOrigW + dx * 1280f / vpW).coerceAtLeast(1f)
            val newH = if (decorResizeOrigH <= 1f) (decorResizeOrigH + dy / vpH).coerceAtLeast(0.02f) else (decorResizeOrigH + dy * 720f / vpH).coerceAtLeast(1f)
            decorations[resizingDecorIdx] = dec.copy(width = newW, height = newH)
            decorData = DecorationData(decorations, decorData.screenEffects); syncDecorRenderer(); unsaved = true; return
        }
        if (draggingDecorIdx >= 0 && decorMode) {
            val vpW = cachedVpW; val vpX = cachedVpX; val scaleRatioX = vpW / 1280f; val scaleRatioY = cachedVpH / 720f
            val newX = (mx - vpX) / (1280f * scaleRatioX) + decorDragOffsetX; val newY = (my - cachedVpY) / (720f * scaleRatioY) + decorDragOffsetY
            decorations[draggingDecorIdx] = decorations[draggingDecorIdx].copy(x = newX.coerceIn(0f, 1f), y = newY.coerceIn(0f, 1f))
            decorData = DecorationData(decorations, decorData.screenEffects); syncDecorRenderer(); unsaved = true; return
        }
        if (timelineDecorDragIdx >= 0 && decorMode) {
            val dec = decorations.getOrNull(timelineDecorDragIdx) ?: run { timelineDecorDragIdx = -1; return }
            val newTimeMs = timelineScrollMs + ((mx - timelineDecorDragOffsetX - timelineX).toDouble() / timelineW * visibleWindowMs).toLong()
            decorations[timelineDecorDragIdx] = dec.copy(timeMs = snapTime(newTimeMs.coerceAtLeast(0L)))
            decorData = DecorationData(decorations, decorData.screenEffects); syncDecorRenderer(); unsaved = true; return
        }
        if (noteResizeIdx >= 0 && button == Keys.MOUSE_LEFT) {
            val note = mutableChart.notes.getOrNull(noteResizeIdx)
            if (note != null) {
                note.type = NoteType.LONG; note.endTime = snapTime((timelineScrollMs + (mx - timelineX).toLong() * visibleWindowMs / tlW).coerceAtLeast(note.time + 50L))
                noteResizeMoved = true
            } else noteResizeIdx = -1
            return
        }
        if (my < unifiedTlY || my >= overviewBarY - overviewBarGap || mx < timelineX || mx > timelineX + timelineW) return
        if (button == Keys.MOUSE_MIDDLE || button == Keys.MOUSE_RIGHT) {
            if (timelineDragStartX >= 0) { val dx = mx - timelineDragStartX; timelineDragStartX = mx; timelineScrollMs = clampTimelineScroll(timelineScrollMs - (dx.toLong() * visibleWindowMs / timelineW)) }
        } else if (button == Keys.MOUSE_LEFT) {
            pendingSeekMs = maxOf(0L, (timelineScrollMs + (mx - timelineX).toLong() * visibleWindowMs / timelineW) + mutableChart.offsetMs)
            timelineDragStartX = mx
        }
    }

    private fun openSettings() { ctx.stateManager.changeState(io.github.jwyoon1220.app.ecs.SettingsScene(ctx, this)) }
    private fun openCalibration() { ctx.stateManager.changeState(io.github.jwyoon1220.app.ecs.SettingsScene(ctx, this, startAt = 1)) }
    private fun cycleNoteMode() { noteMode = if (noteMode == NoteMode.NORMAL) NoteMode.LONG else NoteMode.NORMAL }

    private fun save() { runCatching { ChartParser.serializeChart(mutableChart.toChart(), chartFile) }.onSuccess { unsaved = false } }
    private fun saveDecor() {
        decorData = DecorationData(decorations = decorations, screenEffects = decorData.screenEffects)
        syncDecorRenderer(); runCatching { DecorationParser.serialize(decorData, songEntry.songDir) }
    }
    private fun syncDecorRenderer() { decorRenderer = DecorationRenderer(decorData, songEntry.songDir) }
    private fun toggleDecorMode() {
        decorMode = !decorMode; selectedDecorIdx = -1
        if (decorMode) { decorData = DecorationParser.parseOrNull(songEntry.songDir) ?: DecorationData(); syncDecorRenderer() }
    }

    private fun renderNoteShortcutPanel(g: DrawContext, px: Int, py: Int, pw: Int, ph: Int) {
        g.font = toolFont; var ty = py + 18
        fun section(title: String) { g.color = Color(130, 100, 200); g.drawString(title, px + 10, ty); ty += 20; g.color = Color(40, 40, 65); g.drawLine(px + 8, ty - 4, px + pw - 4, ty - 4); ty += 4 }
        fun shortcut(keys: String, desc: String) { g.color = Color(255, 220, 80); g.drawString(keys, px + 10, ty); g.color = Color(180, 180, 200); g.drawString(desc, px + 90, ty); ty += 18 }
        section("재생 / 탐색"); shortcut("Space", "재생 / 일시정지"); shortcut("J", "5초 뒤로"); shortcut("← / →", "±1초"); shortcut("Shift←→", "±100ms"); shortcut("Home / End", "시작 / 끝"); ty += 4
        section("선택 / 편집"); shortcut("Ctrl+A", "전체 선택"); shortcut("Tab / ↑Tab", "노트 이동"); shortcut("Ctrl+Z / Y", "취소 / 재실행"); shortcut("Ctrl+C / V", "복사 / 붙여넣기"); shortcut("Delete", "삭제"); ty += 4
        section("노트 / 퀘다이즈"); shortcut("R", "녹음 모드"); shortcut("N", "노트 타입"); shortcut("Q / 4 / 8 / 6", "스냅"); shortcut("= / -", "줌 in / out"); ty += 4
        section("기타"); shortcut("Ctrl+S", "저장"); shortcut("Ctrl+Shift+D", "장식 모드"); shortcut("Esc", "뒤로")
    }

    private fun renderDecorPropsPanel(g: DrawContext, px: Int, py: Int, pw: Int, ph: Int) {
        val list = decorations; val selDec = list.getOrNull(selectedDecorIdx); g.font = toolFont; var ty = py + 18
        g.color = Color(195, 130, 255); g.font = headerFont; g.drawString("田 장식 편집", px + 8, ty); ty += 24
        g.font = hintFont; g.color = Color(100, 85, 140); g.drawString("Ctrl+Shift+D: 노트 모드   Ctrl+S: 저장", px + 8, ty); ty += 16
        g.drawString("우클릭: 추가   E: 편집   Del: 삭제", px + 8, ty); ty += 10
        g.color = Color(50, 40, 72); g.drawLine(px + 8, ty - 2, px + pw - 8, ty - 2); ty += 10
        if (selDec == null) { g.color = Color(80, 70, 110); g.font = toolFont; g.drawString("장식을 선택하세요", px + 8, ty); ty += 18; g.color = Color(60, 55, 90); g.drawString("하단 타임라인에서 클릭", px + 8, ty) }
        else {
            g.color = Color(195, 145, 255); g.font = headerFont; g.drawString(selDec.id.ifEmpty { "(unnamed)" }, px + 8, ty); ty += 22; g.font = toolFont
            fun propRow(label: String, value: String) { g.color = Color(130, 105, 185); g.drawString(label, px + 8, ty); g.color = Color(225, 225, 245); g.drawString(value, px + 100, ty); ty += 18 }
            propRow("image", selDec.image.ifEmpty { "(없음)" }); propRow("timeMs", "${selDec.timeMs} ms"); propRow("durMs", "${selDec.durationMs} ms"); propRow("x / y", "%.3f / %.3f".format(selDec.x, selDec.y))
            propRow("w / h", "${selDec.width} / ${selDec.height}"); propRow("opacity", "%.2f".format(selDec.opacity)); propRow("rotation", "%.1f°".format(selDec.rotation)); propRow("depth", "${selDec.depth}"); propRow("effects", "${selDec.effects.size}개")
            ty += 6; g.color = Color(70, 58, 100); g.font = hintFont; g.drawString("E: 편집   Del: 삭제", px + 8, ty)
        }
        ty += 10; g.color = Color(50, 40, 72); g.drawLine(px + 8, ty - 2, px + pw - 8, ty - 2); ty += 8; g.color = Color(80, 65, 115); g.font = hintFont; g.drawString("장식 ${list.size}개", px + 8, ty)
    }

    fun mouseMoved(x: Float, y: Float) {}
    override fun mouseScrolled(dy: Double) { if (dy > 0) zoomIn() else zoomOut() }

    private fun openDecorEditDialog(idx: Int) {
        val dec = decorations.getOrNull(idx) ?: return
        imageBrowserOpen = false
        editingDecorIdx = idx; editingDecor = dec; imId.set(dec.id); imImage.set(dec.image); imTime.set(dec.timeMs.toInt()); imDuration.set(dec.durationMs.toInt())
        imX.set(dec.x); imY.set(dec.y); imW.set(dec.width); imH.set(dec.height); imOpacity.set(dec.opacity); imRotation.set(dec.rotation); imDepth.set(dec.depth)
    }

    private fun openNewDecorDialog(timeMs: Long) {
        val dec = Decoration(id = "new_dec", timeMs = timeMs, durationMs = 1000L)
        imageBrowserOpen = false
        editingDecorIdx = -2; editingDecor = dec; imId.set(dec.id); imImage.set(dec.image); imTime.set(dec.timeMs.toInt()); imDuration.set(dec.durationMs.toInt())
        imX.set(dec.x); imY.set(dec.y); imW.set(dec.width); imH.set(dec.height); imOpacity.set(dec.opacity); imRotation.set(dec.rotation); imDepth.set(dec.depth)
    }

    private fun openImageBrowser() {
        imageBrowserDir = resolveImageBrowserStartDir()
        imageBrowserSelectedFile = resolveSongRelativeFile(imImage.get())?.takeIf { it.isFile }
        refreshImageBrowserEntries()
        imageBrowserOpen = true
    }

    private fun closeImageBrowser() {
        imageBrowserOpen = false
        imageBrowserSelectedFile = null
    }

    private fun resolveImageBrowserStartDir(): File {
        val current = resolveSongRelativeFile(imImage.get())
        return when {
            current?.isDirectory == true -> current
            current?.parentFile?.isDirectory == true -> current.parentFile
            else -> songEntry.songDir
        } ?: songEntry.songDir
    }

    private fun resolveSongRelativeFile(relativeOrAbsolute: String): File? {
        val trimmed = relativeOrAbsolute.trim()
        if (trimmed.isEmpty()) return null
        val file = File(trimmed)
        return if (file.isAbsolute) file else File(songEntry.songDir, trimmed)
    }

    private fun refreshImageBrowserEntries() {
        val directory = imageBrowserDir.takeIf { it.exists() && it.isDirectory } ?: songEntry.songDir
        imageBrowserDir = directory
        val listed = directory.listFiles().orEmpty()
            .filter { child ->
                child.isDirectory || child.extension.lowercase() in IMAGE_FILE_EXTENSIONS
            }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))

        imageBrowserEntries = listed.map { file ->
            ImageBrowserEntry(
                file = file,
                displayName = if (file.isDirectory) "[DIR] ${file.name}" else file.name,
                isDirectory = file.isDirectory
            )
        }

        if (imageBrowserSelectedFile?.exists() != true) imageBrowserSelectedFile = null
    }

    private fun selectImageFromBrowser(file: File) {
        val relative = runCatching {
            file.toRelativeString(songEntry.songDir).replace("\\", "/")
        }.getOrDefault(file.absolutePath)
        imImage.set(relative)
        closeImageBrowser()
    }

    private fun renderImageBrowser() {
        if (!imageBrowserOpen) return

        ImGui.setNextWindowSize(560f, 420f)
        if (ImGui.begin("Image Browser", ImGuiWindowFlags.NoCollapse)) {
            ImGui.textWrapped("곡 폴더 기준 이미지 파일을 선택합니다. 전체화면에서도 동일하게 동작합니다.")
            ImGui.separator()
            ImGui.textWrapped(imageBrowserDir.absolutePath)

            if (ImGui.button("상위 폴더")) {
                val parent = imageBrowserDir.parentFile
                if (parent != null && parent.exists()) {
                    imageBrowserDir = parent
                    imageBrowserSelectedFile = null
                    refreshImageBrowserEntries()
                }
            }
            ImGui.sameLine()
            if (ImGui.button("곡 폴더")) {
                imageBrowserDir = songEntry.songDir
                imageBrowserSelectedFile = null
                refreshImageBrowserEntries()
            }
            ImGui.sameLine()
            if (ImGui.button("새로고침")) refreshImageBrowserEntries()

            ImGui.separator()
            ImGui.beginChild("ImageBrowserEntries", 0f, 270f, true)
            if (imageBrowserEntries.isEmpty()) {
                ImGui.textWrapped("현재 폴더에 선택 가능한 이미지가 없습니다.")
            } else {
                for (entry in imageBrowserEntries) {
                    val isSelected = imageBrowserSelectedFile?.absolutePath == entry.file.absolutePath
                    if (ImGui.selectable(entry.displayName, isSelected)) {
                        if (entry.isDirectory) {
                            imageBrowserDir = entry.file
                            imageBrowserSelectedFile = null
                            refreshImageBrowserEntries()
                        } else {
                            imageBrowserSelectedFile = entry.file
                        }
                    }
                }
            }
            ImGui.endChild()

            val selectedLabel = imageBrowserSelectedFile?.let {
                runCatching { it.toRelativeString(songEntry.songDir).replace("\\", "/") }.getOrDefault(it.absolutePath)
            } ?: "선택된 파일 없음"
            ImGui.textWrapped(selectedLabel)

            if (ImGui.button("선택", 120f, 30f)) {
                imageBrowserSelectedFile?.let(::selectImageFromBrowser)
            }
            ImGui.sameLine()
            if (ImGui.button("취소", 120f, 30f)) closeImageBrowser()
            ImGui.end()
        }
    }

    override fun renderImGui() {
        // 입력 콜백에서 설정된 팝업 요청을 ImGui 프레임 내에서 처리합니다.
        if (pendingContextMenu) { ImGui.openPopup("EditorContextMenu"); pendingContextMenu = false }
        if (ImGui.beginPopup("EditorContextMenu")) {
            if (contextMenuNoteIdx >= 0) {
                if (ImGui.menuItem("노트 삭제")) { saveSnapshot(); synchronized(notesLock) { mutableChart.notes.removeAt(contextMenuNoteIdx) }; selectedIndices.clear(); unsaved = true }
            } else if (contextMenuDecorIdx >= 0) {
                if (ImGui.menuItem("장식 편집")) openDecorEditDialog(contextMenuDecorIdx)
                if (ImGui.menuItem("장식 삭제")) { decorations.removeAt(contextMenuDecorIdx); selectedDecorIdx = -1; unsaved = true; saveDecor() }
            } else {
                if (decorMode) { if (ImGui.menuItem("여기에 장식 추가")) openNewDecorDialog(contextMenuClickMs) }
                else {
                    val laneChar = arrayOf("D","F","J","K")[contextMenuLane]
                    if (ImGui.menuItem("$laneChar 레인에 노트 추가")) {
                        saveSnapshot(); synchronized(notesLock) {
                            mutableChart.notes.add(MutableNote(contextMenuClickMs, contextMenuLane, if (noteMode == NoteMode.NORMAL) NoteType.SHORT else NoteType.LONG, if (noteMode == NoteMode.NORMAL) null else contextMenuClickMs + 500))
                            mutableChart.notes.sortBy { it.time }
                        }; unsaved = true
                    }
                }
            }
            ImGui.separator(); if (ImGui.menuItem("취소")) ImGui.closeCurrentPopup(); ImGui.endPopup()
        }

        if (editingDecorIdx != -1) {
            ImGui.setNextWindowSize(400f, 520f)
            if (ImGui.begin("Decoration Editor", ImGuiWindowFlags.NoCollapse)) {
                ImGui.inputText("ID", imId); ImGui.inputText("Image", imImage)
                if (ImGui.button("파일 선택...")) {
                    openImageBrowser()
                }
                ImGui.separator(); ImGui.inputInt("Time (ms)", imTime); ImGui.inputInt("Duration (ms)", imDuration)
                ImGui.separator(); ImGui.sliderFloat("X", imX.data, 0f, 1f); ImGui.sliderFloat("Y", imY.data, 0f, 1f); ImGui.inputFloat("Width", imW); ImGui.inputFloat("Height", imH)
                ImGui.separator(); ImGui.sliderFloat("Opacity", imOpacity.data, 0f, 1f); ImGui.dragFloat("Rotation", imRotation.data, 0.5f, -360f, 360f); ImGui.inputInt("Depth (Z-index)", imDepth)
                if (ImGui.button("확인", 120f, 30f)) {
                    val updated = Decoration(id = imId.get(), image = imImage.get(), timeMs = imTime.get().toLong(), durationMs = imDuration.get().toLong(), x = imX.get(), y = imY.get(), width = imW.get(), height = imH.get(), opacity = imOpacity.get(), rotation = imRotation.get(), depth = imDepth.get(), effects = editingDecor?.effects ?: emptyList())
                    if (editingDecorIdx == -2) { decorations.add(updated); decorations.sortBy { it.timeMs } } else { decorations[editingDecorIdx] = updated }
                    saveDecor(); unsaved = true; editingDecorIdx = -1; closeImageBrowser()
                }
                ImGui.sameLine(); if (ImGui.button("취소", 120f, 30f)) { editingDecorIdx = -1; closeImageBrowser() }
                ImGui.end()
            }
        }

        renderImageBrowser()
    }
}
