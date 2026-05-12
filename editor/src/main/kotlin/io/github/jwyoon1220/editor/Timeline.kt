package io.github.jwyoon1220.editor

import io.github.jwyoon1220.core.data.MutableChart
import io.github.jwyoon1220.core.data.NoteType
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.FontRegistry
import java.awt.Color
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
        Color(100, 180, 255),
        Color(100, 255, 160),
        Color(255, 200, 80),
        Color(255, 120, 120)
    )
    private val selectedColor = Color(255, 255, 80)

    fun render(
        g: DrawContext,
        chart: MutableChart,
        timelineScrollMs: Long,
        playheadMs: Long,
        x: Int, y: Int, width: Int, height: Int,
        visibleWindowMs: Long = 6_000L,
        selectedIndices: Set<Int> = emptySet(),
        bpm: Double? = null
    ) {
        val laneCount  = 4
        val laneH      = height / laneCount
        val cursorX    = x + ((playheadMs - timelineScrollMs).toDouble() / visibleWindowMs * width).toInt()

        // 배경
        g.color = Color(18, 18, 32)
        g.fillRect(x, y, width, height)

        // 레인 줄무늬
        for (i in 0 until laneCount) {
            val ly = y + i * laneH
            g.color = if (i % 2 == 0) Color(25, 25, 42) else Color(20, 20, 36)
            g.fillRect(x, ly, width, laneH)
            g.color = Color(50, 50, 72)
            g.drawLine(x, ly, x + width, ly)
        }
        g.color = Color(50, 50, 72)
        g.drawLine(x, y + height, x + width, y + height)

        // BPM 비트 / 서브비트 눈금
        if (bpm != null && bpm > 0) {
            val beatMs   = 60_000.0 / bpm
            val subBeatMs = beatMs / 4.0
            val windowHalf = visibleWindowMs / 2.0

            val firstBeat = floor(timelineScrollMs / subBeatMs).toLong()
            val lastBeat  = ceil((timelineScrollMs + visibleWindowMs) / subBeatMs).toLong()

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
                        g.color = Color(100, 100, 160)
                        g.drawLine(bx, y, bx, y + height)
                        val ms = timeMs.toLong().coerceAtLeast(0)
                        g.drawString("%d:%02d".format(ms / 60_000, (ms % 60_000) / 1000), bx + 2, y + 10)
                    }
                    isBeat -> {
                        g.color = Color(60, 60, 100)
                        g.drawLine(bx, y, bx, y + height)
                    }
                    isHalf -> {
                        g.color = Color(40, 40, 70)
                        g.drawLine(bx, y + laneH / 4, bx, y + height - laneH / 4)
                    }
                    else -> {
                        g.color = Color(30, 30, 55)
                        g.drawLine(bx, y + laneH / 3, bx, y + height - laneH / 3)
                    }
                }
            }
        }

        // 노트 (선택 여부에 따라 강조)
        chart.notes.forEachIndexed { idx, note ->
            val nx = x + ((note.time - timelineScrollMs).toDouble() / visibleWindowMs * width).toInt()
            if (nx < x - 20 || nx > x + width + 20) return@forEachIndexed

            val ly       = y + note.lane * laneH
            val baseColor = laneColors[note.lane % laneColors.size]
            val isSelected = idx in selectedIndices
            val color = if (isSelected) selectedColor else baseColor

            if (note.type == NoteType.SHORT) {
                g.color = color
                g.fillRect(nx - 4, ly + 4, 8, laneH - 8)
                g.color = if (isSelected) Color.WHITE else color.brighter()
                g.drawRect(nx - 4, ly + 4, 8, laneH - 8)
            } else {
                val endMs = note.endTime ?: note.time
                val ex    = x + ((endMs - timelineScrollMs).toDouble() / visibleWindowMs * width).toInt()
                val left  = minOf(nx, ex)
                val bw    = abs(ex - nx).coerceAtLeast(4)
                val alpha = if (isSelected) 200 else 120
                g.color   = Color(color.red, color.green, color.blue, alpha)
                g.fillRect(left, ly + laneH / 3, bw, laneH / 3)
                g.color = color
                g.fillRect(nx - 4, ly + 4, 8, laneH - 8)
                g.fillRect(ex - 4, ly + 4, 8, laneH - 8)
                if (isSelected) {
                    g.color = Color.WHITE
                    g.drawRect(nx - 4, ly + 4, 8, laneH - 8)
                    g.drawRect(ex - 4, ly + 4, 8, laneH - 8)
                }
            }
        }

        // 현재 시간 커서
        g.color = Color(255, 255, 255, 220)
        g.drawLine(cursorX, y, cursorX, y + height)
        g.color = Color(255, 80, 80)
        g.drawLine(cursorX, y, cursorX, y + 6)
        g.drawLine(cursorX, y + height - 6, cursorX, y + height)

        // 레인 라벨
        g.font  = FontRegistry.regular(12f)
        val labels = arrayOf("D", "F", "J", "K")
        for (i in 0 until laneCount) {
            g.color = Color(180, 180, 180)
            g.drawString(labels[i], x + 6, y + i * laneH + laneH / 2 + 5)
        }

        // 시간 표시
        g.font  = FontRegistry.bold(13f)
        g.color = Color(200, 200, 200)
        val pos = maxOf(playheadMs, 0L)
        val ts  = "%d:%02d.%03d".format(pos / 60_000, (pos % 60_000) / 1000, pos % 1000)
        g.drawString(ts, x + 26, y + height - 6)
    }
}
