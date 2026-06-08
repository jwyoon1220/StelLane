package io.github.jwyoon1220.editor

import io.github.jwyoon1220.core.data.MutableChart
import io.github.jwyoon1220.core.data.NoteType
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.FontRegistry
import io.github.jwyoon1220.engine.render.RenderColor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 타임라인 뷰를 DrawContext에 그립니다.
 * 화면 좌측 끝 시간이 [timelineScrollMs]이며, 플레이헤드는 [playheadMs]에 위치합니다.
 * [selectedIndices]: 선택된 노트의 인덱스 집합 (강조 표시됨).
 */
object Timeline {

    private val laneColors = arrayOf(
        RenderColor.of(100, 180, 255),
        RenderColor.of(100, 255, 160),
        RenderColor.of(255, 200, 80),
        RenderColor.of(255, 120, 120)
    )
    private val laneColorsBright = Array(4) { laneColors[it].brighter() }
    private val selectedColor = RenderColor.of(255, 255, 80)

    private var cachedPlayheadMs = -1L
    private var cachedPlayheadString = ""
    private val measureStringCache = object : LinkedHashMap<Long, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, String>) = size > 512
    }

    fun render(
        g: DrawContext,
        chart: MutableChart,
        timelineScrollMs: Long,
        playheadMs: Long,
        x: Int, y: Int, width: Int, height: Int,
        visibleWindowMs: Long = 6_000L,
        selectedIndices: Set<Int> = emptySet(),
        bpm: Double? = null,
        drawNotes: Boolean = true
    ) {
        val laneCount  = 4
        val laneH      = height / laneCount
        val cursorX    = x + ((playheadMs - timelineScrollMs).toDouble() / visibleWindowMs * width).toInt()

        // 배경
        g.renderColor = RenderColor.of(18, 18, 32)
        g.fillRect(x, y, width, height)

        // 레인 줄무늬
        for (i in 0 until laneCount) {
            val ly = y + i * laneH
            g.renderColor = if (i % 2 == 0) RenderColor.of(25, 25, 42) else RenderColor.of(20, 20, 36)
            g.fillRect(x, ly, width, laneH)
            g.renderColor = RenderColor.of(50, 50, 72)
            g.drawLine(x, ly, x + width, ly)
        }
        g.renderColor = RenderColor.of(50, 50, 72)
        g.drawLine(x, y + height, x + width, y + height)

        // BPM 비트 / 서브비트 눈금
        if (bpm != null && bpm > 0) {
            val beatMs   = 60_000.0 / bpm
            val subBeatMs = beatMs / 4.0
            val windowHalf = visibleWindowMs / 2.0

            val firstBeat = floor(timelineScrollMs / subBeatMs).toLong()
            val lastBeat  = ceil((timelineScrollMs + visibleWindowMs) / subBeatMs).toLong()

            if (lastBeat - firstBeat in 0..1000) {
                g.font = FontRegistry.regular(10f)
                for (beatIdx in firstBeat..lastBeat) {
                    val timeMs = beatIdx * subBeatMs
                    val bx = x + ((timeMs - timelineScrollMs) / visibleWindowMs * width).toInt()
                    if (bx < x || bx > x + width) continue

                val isMeasure = beatIdx % 16 == 0L
                val isBeat    = beatIdx % 4 == 0L
                val isHalf    = beatIdx % 2 == 0L

                when {
                    isMeasure -> {
                        g.renderColor = RenderColor.of(100, 100, 160)
                        g.drawLine(bx, y, bx, y + height)
                        val ms = timeMs.toLong().coerceAtLeast(0)
                        val label = measureStringCache.getOrPut(ms) {
                            val min = ms / 60_000
                            val sec = (ms % 60_000) / 1000
                            val secStr = if (sec < 10) "0$sec" else "$sec"
                            "$min:$secStr"
                        }
                        g.drawString(label, bx + 2, y + 10)
                    }
                    isBeat -> {
                        g.renderColor = RenderColor.of(60, 60, 100)
                        g.drawLine(bx, y, bx, y + height)
                    }
                    isHalf -> {
                        g.renderColor = RenderColor.of(40, 40, 70)
                        g.drawLine(bx, y + laneH / 4, bx, y + height - laneH / 4)
                    }
                    else -> {
                        g.renderColor = RenderColor.of(30, 30, 55)
                        g.drawLine(bx, y + laneH / 3, bx, y + height - laneH / 3)
                    }
                }
            }
        }
    }

        // 노트 (선택 여부에 따라 강조)
        if (drawNotes) chart.notes.forEachIndexed { idx, note ->
            val nx = x + ((note.time - timelineScrollMs).toDouble() / visibleWindowMs * width).toInt()
            if (nx < x - 20 || nx > x + width + 20) return@forEachIndexed

            val ly       = y + note.lane * laneH
            val baseColor = laneColors[note.lane % laneColors.size]
            val isSelected = idx in selectedIndices
            val color = if (isSelected) selectedColor else baseColor

            if (note.type == NoteType.SHORT) {
                g.renderColor = color
                g.fillRect(nx - 4, ly + 4, 8, laneH - 8)
                g.renderColor = if (isSelected) RenderColor.WHITE else laneColorsBright[note.lane % laneColors.size]
                g.drawRect(nx - 4, ly + 4, 8, laneH - 8)
            } else {
                val endMs = note.endTime ?: note.time
                val ex    = x + ((endMs - timelineScrollMs).toDouble() / visibleWindowMs * width).toInt()
                val left  = minOf(nx, ex)
                val bw    = abs(ex - nx).coerceAtLeast(4)
                val alpha = if (isSelected) 200 else 120
                g.renderColor = color.withAlpha(alpha)
                g.fillRect(left, ly + laneH / 3, bw, laneH / 3)
                g.renderColor = color
                g.fillRect(nx - 4, ly + 4, 8, laneH - 8)
                g.fillRect(ex - 4, ly + 4, 8, laneH - 8)
                if (isSelected) {
                    g.renderColor = RenderColor.WHITE
                    g.drawRect(nx - 4, ly + 4, 8, laneH - 8)
                    g.drawRect(ex - 4, ly + 4, 8, laneH - 8)
                }
            }
        }

        // 현재 시간 커서
        g.renderColor = RenderColor.of(255, 255, 255, 220)
        g.drawLine(cursorX, y, cursorX, y + height)
        g.renderColor = RenderColor.of(255, 80, 80)
        g.drawLine(cursorX, y, cursorX, y + 6)
        g.drawLine(cursorX, y + height - 6, cursorX, y + height)

        // 레인 라벨
        g.font  = FontRegistry.regular(12f)
        val labels = arrayOf("D", "F", "J", "K")
        for (i in 0 until laneCount) {
            g.renderColor = RenderColor.of(180, 180, 180)
            g.drawString(labels[i], x + 6, y + i * laneH + laneH / 2 + 5)
        }

        // 시간 표시
        g.font  = FontRegistry.bold(13f)
        g.renderColor = RenderColor.of(200, 200, 200)
        val pos = maxOf(playheadMs, 0L)
        val ts: String
        if (pos == cachedPlayheadMs && cachedPlayheadString.isNotEmpty()) {
            ts = cachedPlayheadString
        } else {
            val min = pos / 60_000
            val sec = (pos % 60_000) / 1000
            val ms = pos % 1000
            val secStr = if (sec < 10) "0$sec" else "$sec"
            val msStr = if (ms < 10) "00$ms" else if (ms < 100) "0$ms" else "$ms"
            ts = "$min:$secStr.$msStr"
            cachedPlayheadMs = pos
            cachedPlayheadString = ts
        }
        g.drawString(ts, x + 26, y + height - 6)
    }
}
