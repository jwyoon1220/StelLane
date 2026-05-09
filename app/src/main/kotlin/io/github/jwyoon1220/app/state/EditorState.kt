package io.github.jwyoon1220.app.state

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
import java.awt.Font
import java.awt.Graphics2D
import java.awt.event.KeyEvent
import java.io.File

class EditorState(
    private val ctx: GameContext,
    private val songEntry: SongEntry,
    private val chartFile: File,
    chart: Chart
) : GameState {

    // ── 에디터 상태 ───────────────────────────────────────────────────────────
    private val mutableChart = MutableChart(
        offsetMs = chart.offsetMs,
        notes    = chart.notes.map { MutableNote(it.time, it.lane, it.type, it.endTime) }.toMutableList()
    )

    private var isPlaying        = false
    private var recordingMode    = false
    private var quantizeEnabled  = true
    private var quantizeDivision = 4          // 1/4 비트
    private var unsaved          = false

    // 레코딩 중 홀드 시작 시각 (-1 = 미홀드)
    private val heldLaneStartMs = LongArray(4) { -1L }

    // ── 폰트 ─────────────────────────────────────────────────────────────────
    private val headerFont = Font("Arial", Font.BOLD, 22)
    private val infoFont   = Font("Arial", Font.PLAIN, 16)
    private val hintFont   = Font("Arial", Font.PLAIN, 13)

    // ── GameState ─────────────────────────────────────────────────────────────

    override fun enter() {
        isPlaying = false
        ctx.videoBackground.stop()
        ctx.inputManager.clearEvents()
        heldLaneStartMs.fill(-1L)
    }

    override fun exit() {
        ctx.videoBackground.stop()
        ctx.inputManager.clearEvents()
    }

    override fun update(deltaTime: Double) {
        // 레코딩 모드: 재생 중일 때만 레인 이벤트 처리
        val events = ctx.inputManager.pollEvents()
        if (recordingMode && isPlaying) {
            val now = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
            for (event in events) {
                val lane = event.lane
                when (event.type) {
                    LaneEventType.PRESS -> heldLaneStartMs[lane] = now

                    LaneEventType.RELEASE -> {
                        val startMs = heldLaneStartMs[lane]
                        if (startMs < 0) continue
                        val duration   = now - startMs
                        val snappedStart = snapTime(startMs)

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
        // 재생 중이 아닐 때도 이벤트를 드레인해 큐가 넘치지 않도록 함
    }

    override fun render(g: Graphics2D) {
        val w = g.clipBounds?.width  ?: 1280
        val h = g.clipBounds?.height ?: 720

        // 배경 오버레이
        g.color = Color(0, 0, 28, 210)
        g.fillRect(0, 0, w, h)

        // 헤더
        g.font  = headerFont
        g.color = Color(200, 160, 255)
        g.drawString("Chart Editor ─ ${songEntry.song.title}", 18, 28)

        // 상태 표시줄
        val currentTimeMs = ctx.videoBackground.getSmoothTimeMs() - mutableChart.offsetMs
        val pos           = maxOf(currentTimeMs, 0L)
        val timeStr       = "%d:%02d.%03d".format(pos / 60_000, (pos % 60_000) / 1000, pos % 1000)

        g.font  = infoFont
        g.color = Color.WHITE
        val status = buildString {
            append("$timeStr  │  ")
            append(if (isPlaying) "▶ PLAYING" else "⏸ PAUSED")
            append("  │  ")
            append(if (recordingMode) "● REC" else "○ REC")
            append("  │  ")
            append(if (quantizeEnabled) "Snap 1/$quantizeDivision" else "Snap OFF")
            if (unsaved) append("  │  *")
        }
        g.drawString(status, 18, 52)

        // 조작 안내
        g.font  = hintFont
        g.color = Color(120, 120, 140)
        g.drawString(
            "Space: Play/Pause  ←/→: ±1s  Shift+←/→: ±100ms  R: Rec  Q: Snap  [4][8][6]: Division  Ctrl+S: Save  Esc: Back",
            18, 72
        )

        // 타임라인 (메인 영역)
        val tlY = 86
        val tlH = h - tlY - 50
        Timeline.render(g, mutableChart, currentTimeMs, 0, tlY, w, tlH)

        // 하단 상태 바
        g.font  = infoFont
        g.color = Color(170, 170, 170)
        g.drawString("Notes: ${mutableChart.notes.size}   offsetMs: ${mutableChart.offsetMs}", 18, h - 22)

        // 퀀타이즈 옵션 표시
        val divOptions = listOf(4, 8, 16)
        var dx = 260
        for (div in divOptions) {
            g.color = if (quantizeDivision == div && quantizeEnabled) Color(255, 220, 80) else Color(130, 130, 130)
            g.drawString("1/$div", dx, h - 22)
            dx += 56
        }
    }

    override fun keyPressed(e: KeyEvent) {
        val ctrl = e.isControlDown
        when {
            e.keyCode == KeyEvent.VK_SPACE -> togglePlay()

            e.keyCode == KeyEvent.VK_LEFT  && e.isShiftDown -> seek(-100L)
            e.keyCode == KeyEvent.VK_RIGHT && e.isShiftDown -> seek(100L)
            e.keyCode == KeyEvent.VK_LEFT                   -> seek(-1_000L)
            e.keyCode == KeyEvent.VK_RIGHT                  -> seek(1_000L)

            e.keyCode == KeyEvent.VK_R -> {
                recordingMode = !recordingMode
                heldLaneStartMs.fill(-1L)
            }

            e.keyCode == KeyEvent.VK_Q -> cycleQuantize()

            // 숫자 키로 분할 선택 (4 / 8 / 6→16)
            e.keyCode == KeyEvent.VK_4 -> { quantizeEnabled = true; quantizeDivision = 4  }
            e.keyCode == KeyEvent.VK_8 -> { quantizeEnabled = true; quantizeDivision = 8  }
            e.keyCode == KeyEvent.VK_6 -> { quantizeEnabled = true; quantizeDivision = 16 }

            ctrl && e.keyCode == KeyEvent.VK_S -> save()

            e.keyCode == KeyEvent.VK_ESCAPE -> ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.EDIT))
        }
    }

    // ── 내부 동작 ─────────────────────────────────────────────────────────────

    private fun togglePlay() {
        if (isPlaying) {
            ctx.videoBackground.pause()
            isPlaying = false
            recordingMode = false
            heldLaneStartMs.fill(-1L)
        } else {
            val path = resolveMediaPath()
            if (path != null) ctx.videoBackground.play(path)
            isPlaying = true
        }
    }

    private fun seek(deltaMs: Long) {
        val cur = ctx.videoBackground.getSmoothTimeMs()
        ctx.videoBackground.seek(maxOf(0L, cur + deltaMs))
    }

    private fun cycleQuantize() {
        val options = listOf(4, 8, 16)
        if (!quantizeEnabled) {
            quantizeEnabled = true; quantizeDivision = 4; return
        }
        val idx = options.indexOf(quantizeDivision)
        if (idx == options.lastIndex) quantizeEnabled = false
        else quantizeDivision = options[idx + 1]
    }

    private fun snapTime(timeMs: Long): Long {
        if (!quantizeEnabled) return timeMs
        val bpm = songEntry.song.bpm ?: return timeMs
        return Quantizer.snap(timeMs, bpm, quantizeDivision)
    }

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
