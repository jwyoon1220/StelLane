package io.github.jwyoon1220.editor.render

import io.github.jwyoon1220.engine.FontRegistry
import io.github.jwyoon1220.editor.EditorUtils
import io.github.jwyoon1220.editor.comp.*
import io.github.jwyoon1220.core.data.MutableChart
import io.github.jwyoon1220.core.data.NoteType
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand
import it.unimi.dsi.fastutil.ints.IntArraySet
import kotlin.math.abs

/**
 * 타임라인 전체 — 시간 눈금자, 레인 트랙(D/F/J/K), 장식 트랙, 플레이헤드, 트랙 헤더.
 */
class TimelineRenderSystem(
    private val entity: Long,
    private val chart: MutableChart,
    private val notesLock: Any,
    private val bpm: Double?,
    private val offsetMs: Long,
) : RenderProducer {

    private val trackHeaderFont = FontRegistry.semiBold(12f)
    private val rulerFont       = FontRegistry.light(10f)
    private val hintFont        = FontRegistry.light(11f)

    private companion object {
        val LANE_COLORS = arrayOf(
            RenderColor.of(80, 160, 255), RenderColor.of(80, 240, 140),
            RenderColor.of(255, 185, 60), RenderColor.of(255, 100, 100)
        )
        val LANE_LABELS = arrayOf("D", "F", "J", "K")
        val LANE_BG = arrayOf(
            RenderColor.of(18, 22, 42), RenderColor.of(16, 28, 24),
            RenderColor.of(30, 26, 12), RenderColor.of(30, 16, 16)
        )
        val SELECTED_COLOR = RenderColor.of(255, 255, 60, 230)
    }

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit

    override fun produce(world: World, out: MutableList<RenderCommand>) {
        val layout = world.require<LayoutComp>(entity)
        val tl     = world.require<TimelineViewComp>(entity)
        val pb     = world.require<PlaybackComp>(entity)
        val sel    = world.require<SelectionComp>(entity)
        val st     = world.require<EditorStateComp>(entity)
        val dec    = world.require<DecorationRendererComp>(entity)

        val tlX  = layout.tlX;  val tlW = layout.tlW
        val thW  = layout.trackHeaderW
        val rY   = layout.rulerY; val rH = layout.rulerH
        val trY  = layout.trackY
        val lH   = layout.noteLaneH
        val dcY  = layout.decorTrackY; val dcH = layout.decorTlH
        val ovY  = layout.overviewY
        val curT = pb.currentTimeMs
        val scrollMs = tl.scrollMs; val visMs = tl.visibleMs
        val decorMode = st.mode == EditorMode.DECOR
        val decorations = dec.decorations.toList()
        val selIndices  = IntArraySet(sel.selectedIndices)
        val selectedDecorIdx = world.require<DecorationSelectionComp>(entity).selectedIdx

        fun msToX(ms: Long) = tlX + ((ms - scrollMs).toDouble() / visMs * tlW).toInt()

        out.add(RenderCommand.LegacyDrawContext {

            val g = this


            // ── 트랙 헤더 배경 ──────────────────────────────────────────────
            g.renderColor = RenderColor.of(12, 8, 24)
            g.fillRect(0, rY, thW, ovY - rY)
            g.renderColor = RenderColor.of(55, 40, 90, 80)
            g.drawLine(thW, rY, thW, ovY)

            // ── 눈금자 (Ruler) ──────────────────────────────────────────────
            g.renderColor = RenderColor.of(22, 18, 38)
            g.fillRect(tlX, rY, tlW, rH)
            g.font = rulerFont

            if (bpm != null && bpm > 0) {
                val beatMs = 60_000.0 / bpm
                val stepMs = beatMs / 4.0
                val first = (scrollMs / stepMs).toLong() - 1
                val last  = ((scrollMs + visMs) / stepMs).toLong() + 1
                if (last - first in 0..2000) {
                    for (i in first..last) {
                        val tMs = i * stepMs
                        val bx  = tlX + ((tMs - scrollMs) / visMs * tlW).toInt()
                        if (bx < tlX || bx > tlX + tlW) continue
                        val isBeat    = i % 4 == 0L
                        val isMeasure = i % 16 == 0L
                        when {
                            isMeasure -> {
                                g.renderColor = RenderColor.of(100, 80, 160)
                                g.drawLine(bx, rY, bx, rY + rH)
                                g.renderColor = RenderColor.of(180, 155, 230)
                                g.drawString(EditorUtils.formatTime(tMs.toLong()).dropLast(4), (bx + 2).toFloat(), (rY + rH - 3).toFloat())
                            }
                            isBeat -> {
                                g.renderColor = RenderColor.of(55, 45, 90)
                                g.drawLine(bx, rY + rH / 3, bx, rY + rH)
                            }
                            else -> {
                                g.renderColor = RenderColor.of(35, 28, 60)
                                g.drawLine(bx, rY + rH / 2, bx, rY + rH)
                            }
                        }
                    }
                }
            } else {
                // BPM 없을 때 1초 단위 눈금
                val stepSec = if (visMs <= 4000L) 1L else if (visMs <= 10000L) 2L else 5L
                val stepMs  = stepSec * 1000L
                val first = (scrollMs / stepMs) * stepMs
                var t = first
                while (t <= scrollMs + visMs) {
                    val bx = tlX + ((t - scrollMs).toDouble() / visMs * tlW).toInt()
                    if (bx in tlX..(tlX + tlW)) {
                        g.renderColor = RenderColor.of(55, 45, 90); g.drawLine(bx, rY, bx, rY + rH)
                        g.renderColor = RenderColor.of(140, 120, 180)
                        g.drawString(EditorUtils.formatTime(t).dropLast(4), (bx + 2).toFloat(), (rY + rH - 3).toFloat())
                    }
                    t += stepMs
                }
            }

            // ── 노트 레인 트랙 ──────────────────────────────────────────────
            for (lane in 0..3) {
                val ly = layout.laneY(lane)

                // 트랙 배경
                g.renderColor = LANE_BG[lane]; g.fillRect(tlX, ly, tlW, lH)
                g.renderColor = RenderColor.of(40, 32, 62, 120); g.drawLine(tlX, ly, tlX + tlW, ly)

                // BPM 그리드 수직선 (얕게)
                if (bpm != null && bpm > 0) {
                    val beatMs = 60_000.0 / bpm
                    val stepMs = beatMs / 4.0
                    val first = (scrollMs / stepMs).toLong() - 1
                    val last  = ((scrollMs + visMs) / stepMs).toLong() + 1
                    if (last - first in 0..2000) {
                        for (i in first..last) {
                            val bx = tlX + ((i * stepMs - scrollMs) / visMs * tlW).toInt()
                            if (bx < tlX || bx > tlX + tlW) continue
                            val isBeat = i % 4 == 0L
                            g.renderColor = if (isBeat) RenderColor.of(45, 38, 70, 100) else RenderColor.of(30, 25, 52, 70)
                            g.drawLine(bx, ly, bx, ly + lH)
                        }
                    }
                }

                // 트랙 헤더 레이블
                g.renderColor = LANE_COLORS[lane]
                g.font = trackHeaderFont
                g.drawStringCentered(LANE_LABELS[lane], (thW / 2).toFloat(), (ly + lH / 2 + 5).toFloat())
            }

            // 노트 렌더링
            val notesCopy = synchronized(notesLock) { chart.notes.toList() }
            notesCopy.forEachIndexed { idx, note ->
                val nx = msToX(note.time)
                if (nx < tlX - 20 || nx > tlX + tlW + 20) return@forEachIndexed
                val ly = layout.laneY(note.lane)
                val isSelected = idx in selIndices
                val base  = LANE_COLORS[note.lane]
                val color = if (isSelected) SELECTED_COLOR else base

                if (note.type == NoteType.SHORT) {
                    g.renderColor = color; g.fillRect(nx - 4, ly + 4, 8, lH - 8)
                    g.renderColor = if (isSelected) RenderColor.WHITE else base.brighter()
                    g.drawRect(nx - 4, ly + 4, 8, lH - 8)
                } else {
                    val ex = msToX(note.endTime ?: note.time)
                    val left = minOf(nx, ex)
                    val bw   = abs(ex - nx).coerceAtLeast(4)
                    val alpha = if (isSelected) 210 else 130
                    g.renderColor = color.withAlpha(alpha)
                    g.fillRect(left, ly + lH / 3, bw, lH / 3)
                    g.renderColor = color
                    g.fillRect(nx - 4, ly + 4, 8, lH - 8)
                    g.fillRect(ex - 4, ly + 4, 8, lH - 8)
                    if (isSelected) {
                        g.renderColor = RenderColor.WHITE
                        g.drawRect(nx - 4, ly + 4, 8, lH - 8)
                        g.drawRect(ex - 4, ly + 4, 8, lH - 8)
                    }
                }
            }

            // ── 장식 트랙 ─────────────────────────────────────────────────────
            // 트랙 배경
            g.renderColor = RenderColor.of(16, 12, 28); g.fillRect(tlX, dcY, tlW, dcH)
            g.renderColor = RenderColor.of(40, 30, 65, 100); g.drawLine(tlX, dcY, tlX + tlW, dcY)

            // 트랙 헤더 레이블
            g.renderColor = RenderColor.of(160, 100, 255); g.font = trackHeaderFont
            g.drawStringCentered("田", (thW / 2).toFloat(), (dcY + dcH / 2 + 5).toFloat())
            g.font = hintFont; g.renderColor = RenderColor.of(80, 60, 120)
            g.drawStringCentered("DECOR", (thW / 2).toFloat(), (dcY + dcH / 2 + 16).toFloat())

            // 장식 아이템
            val laneH2 = (dcH - 20) / 5
            decorations.forEachIndexed { idx, d ->
                val lane = idx % 5; val by = dcY + 18 + lane * laneH2
                val startPx = msToX(d.timeMs)
                val endPx   = msToX(d.timeMs + d.durationMs)
                if (endPx <= tlX || startPx >= tlX + tlW) return@forEachIndexed
                val bx  = startPx.coerceAtLeast(tlX)
                val bw2 = (endPx - bx).coerceIn(4, tlW - (bx - tlX))
                val hue = (idx * 53) % 360
                val bright = if (idx == selectedDecorIdx) 1.0f else 0.65f
                g.renderColor = RenderColor.fromHSB(hue / 360f, 0.7f, bright)
                g.fillRoundRect(bx, by, bw2, laneH2 - 3, 4, 4)
                if (idx == selectedDecorIdx) {
                    g.renderColor = RenderColor.of(255, 255, 255, 180); g.drawRoundRect(bx, by, bw2, laneH2 - 3, 4, 4)
                }
                // 이름 라벨
                if (bw2 > 30) {
                    g.font = hintFont; g.renderColor = RenderColor.of(0, 0, 0, 160)
                    val label = d.id.ifEmpty { "?" }.let { if (it.length > 10) it.take(9) + "…" else it }
                    g.drawString(label, (bx + 4).toFloat(), (by + laneH2 / 2 + 4).toFloat())
                }
            }

            // ── 플레이헤드 ────────────────────────────────────────────────────
            val phX = msToX(curT)
            if (phX in tlX..(tlX + tlW)) {
                g.renderColor = RenderColor.of(230, 100, 255, 200)
                g.drawLine(phX, rY, phX, ovY - 2)
                g.renderColor = RenderColor.of(230, 100, 255)
                g.fillPolygon(intArrayOf(phX - 5, phX + 5, phX), intArrayOf(rY, rY, rY + 8), 3)
            }

            // ── 타임라인 하단 테두리 ──────────────────────────────────────────
            g.renderColor = RenderColor.of(40, 30, 65, 100)
            g.drawLine(0, ovY - 1, layout.designW, ovY - 1)
        })
    }
}
