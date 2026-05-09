package io.github.jwyoon1220.editor

import io.github.jwyoon1220.core.data.MutableChart
import io.github.jwyoon1220.core.data.NoteType
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import kotlin.math.abs

/**
 * 타임라인 뷰를 Graphics2D에 그립니다.
 * 화면 중앙이 [currentTimeMs]이고, 좌우로 [visibleWindowMs]/2 만큼 보입니다.
 */
object Timeline {

    private val laneColors = arrayOf(
        Color(100, 180, 255),
        Color(100, 255, 160),
        Color(255, 200, 80),
        Color(255, 120, 120)
    )
    private val labelFont = Font("Arial", Font.PLAIN, 12)
    private val timeFont  = Font("Arial", Font.BOLD, 13)

    fun render(
        g: Graphics2D,
        chart: MutableChart,
        currentTimeMs: Long,
        x: Int, y: Int, width: Int, height: Int,
        visibleWindowMs: Long = 6_000L
    ) {
        val laneCount  = 4
        val laneH      = height / laneCount
        val cursorX    = x + width / 2

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

        // BPM 비트 눈금 (bpm 있을 때)
        chart.offsetMs.let { _ ->
            // 눈금은 생략 — bpm이 선택사항이므로 optional 렌더링
        }

        // 노트
        for (note in chart.notes) {
            val offsetMs = note.time - currentTimeMs
            val nx       = cursorX + (offsetMs.toDouble() / visibleWindowMs * width).toInt()
            if (nx < x - 20 || nx > x + width + 20) continue

            val ly    = y + note.lane * laneH
            val color = laneColors[note.lane % laneColors.size]

            if (note.type == NoteType.SHORT) {
                g.color = color
                g.fillRect(nx - 3, ly + 4, 6, laneH - 8)
                g.color = color.brighter()
                g.drawRect(nx - 3, ly + 4, 6, laneH - 8)
            } else {
                // LONG: 바디
                val endMs = note.endTime ?: note.time
                val ex    = cursorX + ((endMs - currentTimeMs).toDouble() / visibleWindowMs * width).toInt()
                val left  = minOf(nx, ex)
                val bw    = abs(ex - nx).coerceAtLeast(4)
                g.color   = Color(color.red, color.green, color.blue, 120)
                g.fillRect(left, ly + laneH / 3, bw, laneH / 3)
                // 헤드 & 테일
                g.color = color
                g.fillRect(nx - 3, ly + 4, 6, laneH - 8)
                g.fillRect(ex - 3, ly + 4, 6, laneH - 8)
            }
        }

        // 현재 시간 커서
        g.color = Color(255, 255, 255, 220)
        g.drawLine(cursorX, y, cursorX, y + height)
        g.color = Color(255, 80, 80)
        g.drawLine(cursorX, y, cursorX, y + 6)
        g.drawLine(cursorX, y + height - 6, cursorX, y + height)

        // 레인 라벨
        g.font  = labelFont
        val labels = arrayOf("D", "F", "J", "K")
        for (i in 0 until laneCount) {
            g.color = Color(180, 180, 180)
            g.drawString(labels[i], x + 6, y + i * laneH + laneH / 2 + 5)
        }

        // 시간 표시
        g.font  = timeFont
        g.color = Color(200, 200, 200)
        val pos = maxOf(currentTimeMs, 0L)
        val ts  = "%d:%02d.%03d".format(pos / 60_000, (pos % 60_000) / 1000, pos % 1000)
        g.drawString(ts, x + 26, y + height - 6)
    }
}
