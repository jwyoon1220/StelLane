package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.core.GameState
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.MutableChart
import io.github.jwyoon1220.core.data.MutableNote
import io.github.jwyoon1220.core.data.NoteType
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.song.ChartParser
import io.github.jwyoon1220.editor.Quantizer
import io.github.jwyoon1220.editor.Timeline
import io.github.jwyoon1220.engine.LaneEventType
import java.awt.Color
import java.awt.Graphics2D
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File

class EditorState(
    private val ctx: GameContext,
    private val songEntry: SongEntry,
    private val chartFile: File,
    chart: Chart
) : GameState {

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
    private val zoomLevels       = listOf(2_000L, 4_000L, 6_000L, 10_000L, 16_000L, 30_000L)
    private var zoomIdx          = 2   // 기본 6s

    // ── 선택 / 클립보드 ───────────────────────────────────────────────────────
    private val selectedIndices  = mutableSetOf<Int>()
    private var cursorNoteIdx    = -1   // Tab 네비게이션 커서
    private var clipboard        = listOf<MutableNote>()
    private var clipboardBaseMs  = 0L   // 복사 시 최소 time

    // ── 실행 취소 / 재실행 ────────────────────────────────────────────────────
    private val undoStack = ArrayDeque<List<MutableNote>>()
    private val redoStack = ArrayDeque<List<MutableNote>>()

    // ── 레이아웃 (render→mouse 공유) ───────────────────────────────────────────
    private var renderW = 1280
    private var renderH = 720
    private val tlY     get() = 86
    private val tlW     get() = renderW - 260
    private val tlH     get() = renderH - tlY - 88
    private val tlCursorX get() = tlW / 2

    // ── 레코딩 (홀드 추적) ────────────────────────────────────────────────────
    private val heldLaneStartMs  = LongArray(4) { -1L }

    // ── 폰트 ─────────────────────────────────────────────────────────────────
    private val headerFont = FontLoader.semiBold(20f)
    private val infoFont   = FontLoader.regular(15f)
    private val hintFont   = FontLoader.light(12f)
    private val toolFont   = FontLoader.regular(13f)

    // ── GameState ─────────────────────────────────────────────────────────────

    override fun enter() {
        isPlaying = false
        ctx.inputManager.clearEvents()
        heldLaneStartMs.fill(-1L)
        selectedIndices.clear()
        cursorNoteIdx = -1

        // VLC 실제 재생 시작 이벤트로 isPlaying 설정 (로딩 딜레이 무관)
        ctx.videoBackground.onPlayingStarted = { isPlaying = true }

        // 에디터 배경으로 영상 재생
        val path = resolveMediaPath()
        if (path != null) ctx.videoBackground.play(path)
    }

    override fun exit() {
        ctx.videoBackground.onPlayingStarted = null
        ctx.videoBackground.stop()
        ctx.inputManager.clearEvents()
    }

    override fun update(deltaTime: Double) {
        val events = ctx.inputManager.pollEvents()
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
                        if (duration < 150) {
                            mutableChart.notes.add(MutableNote(snappedStart, lane, NoteType.SHORT))
                        } else {
                            mutableChart.notes.add(MutableNote(snappedStart, lane, NoteType.LONG, snapTime(now)))
                        }
                        mutableChart.notes.sortBy { it.time }
                        heldLaneStartMs[lane] = -1L
                        unsaved = true
                    }
                }
            }
        }
    }

    override fun render(g: Graphics2D) {
        renderW = g.clipBounds?.width  ?: 1280
        renderH = g.clipBounds?.height ?: 720
        val w = renderW
        val h = renderH

        // ── 배경 오버레이 (영상 위 반투명) ────────────────────────────────────
        g.color = Color(0, 0, 28, 200)
        g.fillRect(0, 0, w, h)

        // ── 상단 헤더 바 ──────────────────────────────────────────────────────
        g.color = Color(20, 20, 40)
        g.fillRect(0, 0, w, 82)
        g.color = Color(60, 60, 100)
        g.drawLine(0, 82, w, 82)

        g.font  = headerFont
        g.color = Color(200, 160, 255)
        g.drawString("✏  ${songEntry.song.title}", 18, 26)

        val currentTimeMs = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
        val pos           = maxOf(currentTimeMs, 0L)
        val timeStr       = "%d:%02d.%03d".format(pos / 60_000, (pos % 60_000) / 1000, pos % 1000)

        g.font  = infoFont
        g.color = Color.WHITE
        val stateStr = buildString {
            append(timeStr)
            append("   ")
            append(if (isPlaying) "▶ PLAY" else "⏸ PAUSE")
            append("   ")
            append(if (recordingMode) "⏺ REC" else "○ REC")
            append("   ")
            append(if (quantizeEnabled) "⊞ Snap 1/$quantizeDivision" else "⊟ Free")
            append("   Zoom ${visibleWindowMs / 1000}s")
            if (unsaved) append("   ●")
        }
        g.drawString(stateStr, 18, 50)

        val selStr = when {
            selectedIndices.isEmpty() -> "No selection"
            selectedIndices.size == 1 -> "1 note selected"
            else                      -> "${selectedIndices.size} notes selected"
        }
        g.font  = hintFont
        g.color = Color(160, 220, 255)
        g.drawString(selStr, 18, 70)

        // ── 타임라인 ──────────────────────────────────────────────────────────
        val tlY = 86
        val tlH = h - tlY - 88
        Timeline.render(
            g, mutableChart, currentTimeMs,
            0, tlY, w - 260, tlH,
            visibleWindowMs, selectedIndices,
            songEntry.song.bpm?.toDouble()
        )

        // ── 오른쪽 도구 패널 ─────────────────────────────────────────────────
        val panelX = w - 256
        g.color = Color(15, 15, 30)
        g.fillRect(panelX, tlY, 256, tlH)
        g.color = Color(50, 50, 80)
        g.drawLine(panelX, tlY, panelX, tlY + tlH)

        g.font  = toolFont
        var ty  = tlY + 18
        fun section(title: String) {
            g.color = Color(130, 100, 200)
            g.drawString(title, panelX + 10, ty); ty += 20
            g.color = Color(40, 40, 65)
            g.drawLine(panelX + 8, ty - 4, w - 10, ty - 4); ty += 4
        }
        fun shortcut(keys: String, desc: String) {
            g.color = Color(255, 220, 80)
            g.drawString(keys, panelX + 10, ty)
            g.color = Color(180, 180, 200)
            g.drawString(desc, panelX + 90, ty)
            ty += 18
        }

        section("재생 / 탐색")
        shortcut("Space",    "재생 / 일시정지")
        shortcut("J",        "5초 뒤로")
        shortcut("K",        "일시정지")
        shortcut("L",        "재생 시작")
        shortcut("← / →",   "±1초")
        shortcut("Shift←→", "±100ms")
        shortcut("Home",     "처음으로")
        shortcut("End",      "끝으로")
        ty += 4

        section("선택")
        shortcut("Ctrl+A",   "전체 선택")
        shortcut("Ctrl+D",   "선택 해제")
        shortcut("Tab",      "다음 노트")
        shortcut("Shift+Tab","이전 노트")
        ty += 4

        section("편집")
        shortcut("Ctrl+Z",   "실행 취소")
        shortcut("Ctrl+Y",   "재실행")
        shortcut("Ctrl+C",   "복사")
        shortcut("Ctrl+X",   "잘라내기")
        shortcut("Ctrl+V",   "붙여넣기")
        shortcut("Delete",   "선택 삭제")
        ty += 4

        section("녹음 / 퀀타이즈")
        shortcut("R",        "녹음 모드")
        shortcut("Q",        "Snap 순환")
        shortcut("4/8/6",    "1/4·1/8·1/16")
        ty += 4

        section("줌 / 저장")
        shortcut("= / +",    "확대")
        shortcut("-",        "축소")
        shortcut("Ctrl+S",   "저장")
        shortcut("Esc",      "뒤로")

        // ── 하단 상태 바 ─────────────────────────────────────────────────────
        val barY = h - 84
        g.color = Color(20, 20, 40)
        g.fillRect(0, barY, w, 84)
        g.color = Color(60, 60, 100)
        g.drawLine(0, barY, w, barY)

        g.font  = infoFont
        g.color = Color(170, 170, 170)
        g.drawString("Notes: ${mutableChart.notes.size}   Offset: ${mutableChart.offsetMs}ms   BPM: ${songEntry.song.bpm ?: "-"}   Undo: ${undoStack.size}", 18, barY + 22)

        // 퀀타이즈 선택 표시
        val divOptions = listOf(4, 8, 16)
        var dx = 18
        g.drawString("Snap:", dx, barY + 46); dx += 54
        for (div in divOptions) {
            g.color = if (quantizeDivision == div && quantizeEnabled) Color(255, 220, 80) else Color(100, 100, 120)
            g.drawString("1/$div", dx, barY + 46); dx += 56
        }
        if (!quantizeEnabled) {
            g.color = Color(255, 220, 80)
            g.drawString("FREE", dx, barY + 46)
        }
    }

    override fun keyPressed(e: KeyEvent) {
        val ctrl  = e.isControlDown
        val shift = e.isShiftDown

        when {
            // 재생 제어 — 녹음 중에는 J/K 가 레인 키로만 동작
            e.keyCode == KeyEvent.VK_SPACE                            -> togglePlay()
            e.keyCode == KeyEvent.VK_J && !ctrl && !recordingMode     -> { pause(); seek(-5_000L) }
            e.keyCode == KeyEvent.VK_K && !recordingMode              -> pause()
            e.keyCode == KeyEvent.VK_L && !ctrl                       -> startPlay()
            e.keyCode == KeyEvent.VK_HOME                      -> seek(-ctx.videoBackground.getSmoothTimeMs())
            e.keyCode == KeyEvent.VK_END                       -> { /* seek to last note */ seekToEnd() }
            e.keyCode == KeyEvent.VK_LEFT  && shift            -> seek(-100L)
            e.keyCode == KeyEvent.VK_RIGHT && shift            -> seek(100L)
            e.keyCode == KeyEvent.VK_LEFT  && !ctrl && !shift  -> seek(-1_000L)
            e.keyCode == KeyEvent.VK_RIGHT && !ctrl && !shift  -> seek(1_000L)

            // 선택
            ctrl && e.keyCode == KeyEvent.VK_A                 -> selectAll()
            ctrl && e.keyCode == KeyEvent.VK_D                 -> selectedIndices.clear()
            e.keyCode == KeyEvent.VK_TAB && !shift             -> navigateNote(+1)
            e.keyCode == KeyEvent.VK_TAB && shift              -> navigateNote(-1)

            // 편집
            ctrl && e.keyCode == KeyEvent.VK_Z && !shift       -> undo()
            ctrl && e.keyCode == KeyEvent.VK_Y                 -> redo()
            ctrl && e.keyCode == KeyEvent.VK_Z && shift        -> redo()
            ctrl && e.keyCode == KeyEvent.VK_C                 -> copy()
            ctrl && e.keyCode == KeyEvent.VK_X                 -> cut()
            ctrl && e.keyCode == KeyEvent.VK_V                 -> paste()
            e.keyCode == KeyEvent.VK_DELETE || e.keyCode == KeyEvent.VK_BACK_SPACE
                                                                -> deleteSelected()

            // 녹음 / 퀀타이즈
            e.keyCode == KeyEvent.VK_R                         -> {
                recordingMode = !recordingMode
                heldLaneStartMs.fill(-1L)
            }
            e.keyCode == KeyEvent.VK_Q                         -> cycleQuantize()
            e.keyCode == KeyEvent.VK_4                         -> { quantizeEnabled = true; quantizeDivision = 4  }
            e.keyCode == KeyEvent.VK_8                         -> { quantizeEnabled = true; quantizeDivision = 8  }
            e.keyCode == KeyEvent.VK_6                         -> { quantizeEnabled = true; quantizeDivision = 16 }

            // 줌
            e.keyCode == KeyEvent.VK_EQUALS || e.keyCode == KeyEvent.VK_PLUS -> zoomIn()
            e.keyCode == KeyEvent.VK_MINUS                     -> zoomOut()

            // 저장 / 뒤로
            ctrl && e.keyCode == KeyEvent.VK_S                 -> save()
            ctrl && e.keyCode == KeyEvent.VK_COMMA             -> openSettings()
            ctrl && shift && e.keyCode == KeyEvent.VK_O        -> openCalibration()
            e.keyCode == KeyEvent.VK_ESCAPE                    -> {
                if (selectedIndices.isNotEmpty()) selectedIndices.clear()
                else ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.EDIT))
            }
        }
    }

    // ── 재생 제어 ─────────────────────────────────────────────────────────────

    private fun togglePlay() {
        if (isPlaying) pause() else startPlay()
    }

    private fun startPlay() {
        if (!isPlaying) {
            // isPlaying 은 onPlayingStarted 콜백으로 설정됨 (VLC 실제 재생 시작 시)
            if (ctx.videoBackground.isPlayable()) {
                ctx.videoBackground.resume()         // 이미 로드된 미디어 이어서 재생
            } else {
                val path = resolveMediaPath()
                if (path != null) ctx.videoBackground.play(path)
            }
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

    // ── 퀀타이즈 / 줌 ────────────────────────────────────────────────────────

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

    override fun mouseClicked(e: MouseEvent) {
        val mx = e.x
        val my = e.y

        // 타임라인 영역 안에서만 처리
        if (my < tlY || my > tlY + tlH) return
        if (mx > tlW)                   return

        val currentTimeMs = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
        val laneH = tlH / 4

        when (e.button) {
            MouseEvent.BUTTON1 -> {
                val hitIdx = findNoteAt(mx, my, currentTimeMs, laneH)
                if (hitIdx >= 0) {
                    // 노트 클릭: 선택 / 추가 선택
                    if (e.isShiftDown) {
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
                    val clickMs = currentTimeMs + (mx - tlCursorX).toLong() * visibleWindowMs / tlW
                    ctx.videoBackground.seek(maxOf(0L, clickMs + mutableChart.offsetMs))
                }
            }
            MouseEvent.BUTTON3 -> {
                // 오른쪽 클릭: 노트 삭제
                val hitIdx = findNoteAt(mx, my, currentTimeMs, laneH)
                if (hitIdx >= 0) {
                    saveSnapshot()
                    mutableChart.notes.removeAt(hitIdx)
                    // 인덱스 재매핑
                    val above = selectedIndices.filter { it > hitIdx }.toSet()
                    selectedIndices.removeAll { it >= hitIdx }
                    selectedIndices.addAll(above.map { it - 1 })
                    unsaved = true
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
            val nx = tlCursorX + ((note.time - currentTimeMs).toDouble() / visibleWindowMs * tlW).toInt()
            if (mx < nx - 6 || mx > nx + 6) return@forEachIndexed
            val ly = tlY + note.lane * laneH
            if (my < ly || my > ly + laneH) return@forEachIndexed
            return idx
        }
        return -1
    }

    // ── 설정 / 보정 다이얼로그 ─────────────────────────────────────────────────

    private fun openSettings() {
        ctx.stateManager.changeState(SettingsState(ctx, this))
    }

    private fun openCalibration() {
        ctx.stateManager.changeState(SettingsState(ctx, this, startAt = 1))
    }

    // ── 저장 / 미디어 ────────────────────────────────────────────────────────

    private fun save() {
        runCatching { ChartParser.serializeChart(mutableChart.toChart(), chartFile) }
            .onSuccess { unsaved = false }
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