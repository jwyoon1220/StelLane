package io.github.jwyoon1220.app.editor.sys

import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.editor.EditorUtils
import io.github.jwyoon1220.app.editor.comp.*
import io.github.jwyoon1220.app.ecs.SelectMode
import io.github.jwyoon1220.app.ecs.SettingsScene
import io.github.jwyoon1220.app.ecs.SongSelectScene
import io.github.jwyoon1220.core.data.Decoration
import io.github.jwyoon1220.core.data.MutableChart
import io.github.jwyoon1220.core.data.MutableNote
import io.github.jwyoon1220.core.data.NoteType
import io.github.jwyoon1220.core.song.ChartParser
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.EcsSystem
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.World
import java.io.File
import kotlin.math.abs

class EditorInputSystem(
    private val ctx: GameContext,
    private val entity: Long,
    private val chart: MutableChart,
    private val notesLock: Any,
    private val bpm: Double?,
    private val songDir: File,
    private val chartFile: File,
    private val offsetMs: Long,
) : EcsSystem {

    // 마우스 상태 추적 (프레임 간)
    private var mouseHeld = false
    private var heldButton = -1
    private var pressCursorX = 0f
    private var pressCursorY = 0f
    private var lastCursorX  = 0f
    private var lastCursorY  = 0f
    private var wasDragging  = false
    private val DRAG_THRESHOLD = 4f

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) {
        val mod = world.require<KeyModComp>(entity)

        for (ev in input.keyEvents) {
            when (ev.action) {
                Keys.PRESS, Keys.REPEAT -> handleKey(world, ev.key, ev.mods)
                Keys.RELEASE -> mod.shiftHeld = Keys.isShift(ev.mods)
            }
        }

        val cx = input.cursorX
        val cy = input.cursorY

        for (ev in input.mouseEvents) {
            when (ev.action) {
                Keys.PRESS -> {
                    mouseHeld    = true
                    heldButton   = ev.button
                    pressCursorX = ev.x
                    pressCursorY = ev.y
                    wasDragging  = false
                    onMousePress(world, ev.x, ev.y, ev.button, ev.mods)
                }
                Keys.RELEASE -> {
                    if (mouseHeld) {
                        if (!wasDragging) onMouseClick(world, ev.x, ev.y, heldButton, ev.mods)
                        else              onMouseRelease(world, ev.x, ev.y, heldButton)
                    }
                    mouseHeld   = false
                    wasDragging = false
                    heldButton  = -1
                }
            }
        }

        if (mouseHeld) {
            if (!wasDragging && (abs(cx - pressCursorX) > DRAG_THRESHOLD || abs(cy - pressCursorY) > DRAG_THRESHOLD))
                wasDragging = true
            if (wasDragging && (cx != lastCursorX || cy != lastCursorY))
                onMouseDrag(world, cx, cy, heldButton)
        }

        if (input.scrollDy != 0.0) onScroll(world, input.scrollDy)

        lastCursorX = cx
        lastCursorY = cy
    }

    // ── 키보드 ──────────────────────────────────────────────────────────────

    private fun handleKey(world: World, key: Int, mods: Int) {
        val ctrl  = Keys.isCtrl(mods)
        val shift = Keys.isShift(mods)
        world.require<KeyModComp>(entity).shiftHeld = shift

        val st  = world.require<EditorStateComp>(entity)
        val pb  = world.require<PlaybackComp>(entity)
        val tl  = world.require<TimelineViewComp>(entity)
        val q   = world.require<QuantizeComp>(entity)
        val sel = world.require<SelectionComp>(entity)
        val ur  = world.require<UndoRedoComp>(entity)
        val cb  = world.require<ClipboardComp>(entity)
        val ds  = world.require<DecorationSelectionComp>(entity)
        val sk  = world.require<SeekComp>(entity)
        val dec = world.require<DecorationRendererComp>(entity)

        if (st.mode == EditorMode.DECOR) {
            when {
                ctrl && shift && key == Keys.D -> toggleDecorMode(world, st, ds)
                ctrl && key == Keys.S          -> dec.save().also { st.unsaved = false }
                key == Keys.E && ds.selectedIdx >= 0 -> { ds.editingIdx = ds.selectedIdx; ds.editingIsNew = false }
                key == Keys.DELETE || key == Keys.BACKSPACE -> deleteSelectedDecor(world, ds, dec, st)
                key == Keys.ESCAPE -> { st.mode = EditorMode.NOTE; ds.selectedIdx = -1 }
                key == Keys.SPACE  -> togglePlay(ctx, pb, songDir.absolutePath)
                key == Keys.LEFT   -> queueSeek(sk, ctx, -1_000L)
                key == Keys.RIGHT  -> queueSeek(sk, ctx, +1_000L)
            }
            return
        }

        when {
            ctrl && shift && key == Keys.D -> toggleDecorMode(world, st, ds)
            key == Keys.SPACE  -> togglePlay(ctx, pb, songDir.absolutePath)
            key == Keys.J && !ctrl && !pb.isRecording -> { pausePlayback(ctx, pb); queueSeek(sk, ctx, -5_000L) }
            key == Keys.K && !pb.isRecording          -> pausePlayback(ctx, pb)
            key == Keys.L && !ctrl                    -> startPlayback(ctx, pb, songDir.absolutePath)
            key == Keys.HOME   -> queueSeekAbs(sk, 0L)
            key == Keys.END    -> queueSeekAbs(sk, seekEndMs())
            shift && key == Keys.LEFT  -> queueSeek(sk, ctx, -100L)
            shift && key == Keys.RIGHT -> queueSeek(sk, ctx, +100L)
            !ctrl && !shift && key == Keys.LEFT  -> queueSeek(sk, ctx, -1_000L)
            !ctrl && !shift && key == Keys.RIGHT -> queueSeek(sk, ctx, +1_000L)
            ctrl && key == Keys.A -> selectAll(sel)
            ctrl && key == Keys.D -> sel.selectedIndices.clear()
            key == Keys.TAB && !shift -> navigateNote(sel, +1)
            key == Keys.TAB && shift  -> navigateNote(sel, -1)
            ctrl && !shift && key == Keys.Z -> doUndo(ur, sel, st)
            ctrl && (key == Keys.Y || (shift && key == Keys.Z)) -> doRedo(ur, sel, st)
            ctrl && key == Keys.C -> doCopy(sel, cb)
            ctrl && key == Keys.X -> { doCopy(sel, cb); doDelete(sel, ur, st) }
            ctrl && key == Keys.V -> doPaste(pb, cb, q, sel, ur, st)
            key == Keys.DELETE || key == Keys.BACKSPACE -> doDelete(sel, ur, st)
            key == Keys.N -> st.noteInputMode = if (st.noteInputMode == NoteInputMode.NORMAL) NoteInputMode.LONG else NoteInputMode.NORMAL
            key == Keys.R -> { pb.isRecording = !pb.isRecording; world.require<RecordingComp>(entity).heldLaneStartMs.fill(-1L) }
            key == Keys.Q -> cycleQuantize(q)
            key == Keys.N4 -> { q.enabled = true; q.division = 4 }
            key == Keys.N8 -> { q.enabled = true; q.division = 8 }
            key == Keys.N6 -> { q.enabled = true; q.division = 16 }
            key == Keys.EQUAL || key == Keys.PLUS -> tl.zoomIn()
            key == Keys.MINUS  -> tl.zoomOut()
            ctrl && key == Keys.S -> save(st)
            ctrl && key == Keys.COMMA -> ctx.sceneRouter.navigate(SettingsScene(ctx, ctx.sceneRouter.current!!))
            ctrl && shift && key == Keys.O -> ctx.sceneRouter.navigate(SettingsScene(ctx, ctx.sceneRouter.current!!, startAt = 1))
            key == Keys.ESCAPE -> {
                if (sel.selectedIndices.isNotEmpty()) sel.selectedIndices.clear()
                else ctx.sceneRouter.navigate(SongSelectScene(ctx, SelectMode.EDIT))
            }
        }
    }

    // ── 마우스 클릭 / 드래그 ────────────────────────────────────────────────

    private fun onMousePress(world: World, x: Float, y: Float, btn: Int, mods: Int) {
        val layout = world.require<LayoutComp>(entity)
        val tl     = world.require<TimelineViewComp>(entity)
        val drag   = world.require<DragStateComp>(entity)
        val pb     = world.require<PlaybackComp>(entity)
        val dec    = world.require<DecorationRendererComp>(entity)
        val ds     = world.require<DecorationSelectionComp>(entity)
        val sk     = world.require<SeekComp>(entity)
        val st     = world.require<EditorStateComp>(entity)
        val mod    = world.require<KeyModComp>(entity)
        mod.shiftHeld = Keys.isShift(mods)

        val mx = x.toInt(); val my = y.toInt()

        // 오버뷰 바 클릭 → 탐색
        if (btn == Keys.MOUSE_LEFT && my in layout.overviewY..(layout.overviewY + layout.overviewBarH)
            && mx in layout.tlX..(layout.tlX + layout.tlW)) {
            world.require<OverviewComp>(entity).dragActive = true
            seekOverview(world, mx, tl, sk, layout, pb)
            return
        }

        // 장식 모드: 뷰포트 내 장식 드래그
        if (st.mode == EditorMode.DECOR) {
            val vp = layout
            if (mx in vp.vpX..(vp.vpX + vp.vpW) && my in vp.vpY..(vp.vpY + vp.vpH)) {
                val selDec = dec.decorations.getOrNull(ds.selectedIdx)
                if (selDec != null) {
                    val sx = vp.vpW / 1280f; val sy = vp.vpH / 720f
                    val bw = (if (selDec.width <= 1f) selDec.width * 1280f else selDec.width) * sx
                    val bh = (if (selDec.height <= 1f) selDec.height * 720f else selDec.height) * sy
                    val lx = (vp.vpX + (selDec.x * 1280f - selDec.pivotX * bw / sx) * sx).toInt()
                    val ly = (vp.vpY + (selDec.y * 720f  - selDec.pivotY * bh / sy) * sy).toInt()
                    val hs = 12
                    if (mx in (lx + bw.toInt() - hs)..(lx + bw.toInt() + hs) && my in (ly + bh.toInt() - hs)..(ly + bh.toInt() + hs)) {
                        drag.type = DragType.DECOR_VP_RESIZE; drag.decorIdx = ds.selectedIdx
                        drag.decorResizePressX = mx; drag.decorResizePressY = my
                        drag.decorResizeOrigW = selDec.width; drag.decorResizeOrigH = selDec.height
                        return
                    }
                    if (mx in lx..(lx + bw.toInt()) && my in ly..(ly + bh.toInt())) {
                        drag.type = DragType.DECOR_VP_MOVE; drag.decorIdx = ds.selectedIdx
                        drag.decorDragOffsetX = selDec.x - (mx - vp.vpX) / (1280f * sx)
                        drag.decorDragOffsetY = selDec.y - (my - vp.vpY) / (720f * sy)
                        return
                    }
                }
            }
        }

        // 타임라인 트랙 클릭
        val tlInBounds = mx in layout.tlX..(layout.tlX + layout.tlW) &&
                         my in layout.tlAreaY..(layout.overviewY)
        if (!tlInBounds) return

        if (st.mode == EditorMode.DECOR && my in layout.decorTrackY..(layout.decorTrackY + layout.decorTlH)) {
            val hit = findDecorAt(mx, my, dec.decorations, layout, tl)
            if (hit >= 0) {
                drag.type = DragType.DECOR_TL_MOVE; drag.decorIdx = hit; ds.selectedIdx = hit
                drag.decorDragOffsetX = (mx - msToScreenX(dec.decorations[hit].timeMs, tl, layout)).toFloat()
                return
            }
        }

        if (my in layout.trackY..(layout.trackY + layout.noteLaneH * 4) && btn == Keys.MOUSE_LEFT) {
            val laneH = layout.noteLaneH
            val hit = findNoteAt(mx, my, layout, tl)
            if (hit >= 0) { drag.type = DragType.NOTE_RESIZE; drag.noteIdx = hit; drag.noteResizeMoved = false }
        }
    }

    private fun onMouseClick(world: World, x: Float, y: Float, btn: Int, mods: Int) {
        val layout = world.require<LayoutComp>(entity)
        val tl     = world.require<TimelineViewComp>(entity)
        val pb     = world.require<PlaybackComp>(entity)
        val sel    = world.require<SelectionComp>(entity)
        val ds     = world.require<DecorationSelectionComp>(entity)
        val ctx2   = world.require<ContextMenuComp>(entity)
        val sk     = world.require<SeekComp>(entity)
        val st     = world.require<EditorStateComp>(entity)
        val dec    = world.require<DecorationRendererComp>(entity)
        val drag   = world.require<DragStateComp>(entity)

        val mx = x.toInt(); val my = y.toInt()

        // 오버뷰 바
        if (my in layout.overviewY..(layout.overviewY + layout.overviewBarH) && mx in layout.tlX..(layout.tlX + layout.tlW)) {
            seekOverview(world, mx, tl, sk, layout, pb); return
        }

        // 장식 모드: 뷰포트 클릭 → 선택
        if (st.mode == EditorMode.DECOR && mx in layout.vpX..(layout.vpX + layout.vpW) && my in layout.vpY..(layout.vpY + layout.vpH)) {
            // 뷰포트 클릭 시 새 장식 선택은 타임라인 트랙에서 처리
        }

        val tlInBounds = mx in layout.tlX..(layout.tlX + layout.tlW)

        // 노트 트랙 클릭
        if (my in layout.trackY..(layout.trackY + layout.noteLaneH * 4) && tlInBounds) {
            val hit = findNoteAt(mx, my, layout, tl)
            if (btn == Keys.MOUSE_LEFT) {
                if (hit >= 0) {
                    if (Keys.isShift(mods)) { if (hit in sel.selectedIndices) sel.selectedIndices.remove(hit) else sel.selectedIndices.add(hit) }
                    else { sel.selectedIndices.clear(); sel.selectedIndices.add(hit); sel.cursorIdx = hit }
                } else {
                    sel.selectedIndices.clear()
                    val clickMs = msFromScreenX(mx, tl, layout)
                    sk.pendingSeekMs = (clickMs + offsetMs).coerceAtLeast(0L)
                }
            } else if (btn == Keys.MOUSE_RIGHT) {
                val clickMs = EditorUtils.snapTime(msFromScreenX(mx, tl, layout), world.require<QuantizeComp>(entity), bpm)
                val lane = ((my - layout.trackY) / layout.noteLaneH).coerceIn(0, 3)
                ctx2.pending = true; ctx2.noteIdx = hit; ctx2.decorIdx = -1
                ctx2.clickMs = clickMs; ctx2.lane = lane
            }
            return
        }

        // 장식 트랙 클릭
        if (st.mode == EditorMode.DECOR && my in layout.decorTrackY..(layout.decorTrackY + layout.decorTlH) && tlInBounds) {
            val hit = findDecorAt(mx, my, dec.decorations, layout, tl)
            if (btn == Keys.MOUSE_LEFT) ds.selectedIdx = hit
            else if (btn == Keys.MOUSE_RIGHT) {
                ctx2.pending = true; ctx2.noteIdx = -1; ctx2.decorIdx = hit
                ctx2.clickMs = msFromScreenX(mx, tl, layout)
            }
        }
    }

    private fun onMouseRelease(world: World, x: Float, y: Float, btn: Int) {
        val drag = world.require<DragStateComp>(entity)
        val st   = world.require<EditorStateComp>(entity)
        val ur   = world.require<UndoRedoComp>(entity)
        val sk   = world.require<SeekComp>(entity)

        if (drag.type == DragType.NOTE_RESIZE && drag.noteResizeMoved) {
            EditorUtils.saveSnapshot(chart, ur, notesLock); st.unsaved = true
        }
        if (sk.pendingSeekMs >= 0L) {
            ctx.videoBackground.seek(sk.pendingSeekMs); sk.pendingSeekMs = -1L
        }
        world.require<OverviewComp>(entity).dragActive = false
        drag.type = DragType.NONE; drag.noteIdx = -1; drag.decorIdx = -1
        drag.noteResizeMoved = false
    }

    private fun onMouseDrag(world: World, x: Float, y: Float, btn: Int) {
        val layout = world.require<LayoutComp>(entity)
        val tl     = world.require<TimelineViewComp>(entity)
        val drag   = world.require<DragStateComp>(entity)
        val pb     = world.require<PlaybackComp>(entity)
        val ds     = world.require<DecorationSelectionComp>(entity)
        val dec    = world.require<DecorationRendererComp>(entity)
        val st     = world.require<EditorStateComp>(entity)
        val sk     = world.require<SeekComp>(entity)
        val q      = world.require<QuantizeComp>(entity)
        val mod    = world.require<KeyModComp>(entity)
        val ov     = world.require<OverviewComp>(entity)

        val mx = x.toInt(); val my = y.toInt()

        if (ov.dragActive && btn == Keys.MOUSE_LEFT) {
            seekOverview(world, mx, tl, sk, layout, pb); return
        }

        when (drag.type) {
            DragType.DECOR_VP_RESIZE -> {
                val d = dec.decorations.getOrNull(drag.decorIdx) ?: return
                val dx = (mx - drag.decorResizePressX).toFloat()
                val dy = (my - drag.decorResizePressY).toFloat()
                val sx = layout.vpW / 1280f; val sy = layout.vpH / 720f
                val newW = if (drag.decorResizeOrigW <= 1f) (drag.decorResizeOrigW + dx / layout.vpW).coerceAtLeast(0.02f)
                           else (drag.decorResizeOrigW + dx * 1280f / layout.vpW).coerceAtLeast(1f)
                val newH = if (mod.shiftHeld) newW / (drag.decorResizeOrigW / drag.decorResizeOrigH.coerceAtLeast(0.001f))
                           else if (drag.decorResizeOrigH <= 1f) (drag.decorResizeOrigH + dy / layout.vpH).coerceAtLeast(0.02f)
                           else (drag.decorResizeOrigH + dy * 720f / layout.vpH).coerceAtLeast(1f)
                dec.decorations[drag.decorIdx] = d.copy(width = newW, height = newH)
                dec.rebuild(); st.unsaved = true
            }
            DragType.DECOR_VP_MOVE -> {
                val d = dec.decorations.getOrNull(drag.decorIdx) ?: return
                val sx = layout.vpW / 1280f; val sy = layout.vpH / 720f
                val nx = (mx - layout.vpX) / (1280f * sx) + drag.decorDragOffsetX
                val ny = (my - layout.vpY) / (720f  * sy) + drag.decorDragOffsetY
                dec.decorations[drag.decorIdx] = d.copy(x = nx.coerceIn(0f,1f), y = ny.coerceIn(0f,1f))
                dec.rebuild(); st.unsaved = true
            }
            DragType.DECOR_TL_MOVE -> {
                val d = dec.decorations.getOrNull(drag.decorIdx) ?: return
                val newMs = (tl.scrollMs + ((mx - drag.decorDragOffsetX - layout.tlX).toDouble() / layout.tlW * tl.visibleMs).toLong()).coerceAtLeast(0L)
                dec.decorations[drag.decorIdx] = d.copy(timeMs = EditorUtils.snapTime(newMs, q, bpm))
                dec.rebuild(); st.unsaved = true
            }
            DragType.NOTE_RESIZE -> {
                val note = synchronized(notesLock) { chart.notes.getOrNull(drag.noteIdx) }
                if (note != null) {
                    val endMs = msFromScreenX(mx, tl, layout).coerceAtLeast(note.time + 50L)
                    note.type = NoteType.LONG; note.endTime = EditorUtils.snapTime(endMs, q, bpm)
                    drag.noteResizeMoved = true
                } else drag.type = DragType.NONE
            }
            DragType.NONE -> {
                if (my < layout.tlAreaY || my >= layout.overviewY) return
                if (mx < layout.tlX || mx > layout.tlX + layout.tlW) return
                when (btn) {
                    Keys.MOUSE_MIDDLE, Keys.MOUSE_RIGHT -> {
                        if (drag.timelineDragStartX >= 0) {
                            val dx = mx - drag.timelineDragStartX
                            tl.scrollMs = clampScroll(tl.scrollMs - (dx.toLong() * tl.visibleMs / layout.tlW), tl, layout)
                            drag.timelineDragStartX = mx
                        } else drag.timelineDragStartX = mx
                    }
                    Keys.MOUSE_LEFT -> {
                        sk.pendingSeekMs = (msFromScreenX(mx, tl, layout) + offsetMs).coerceAtLeast(0L)
                        drag.timelineDragStartX = mx
                    }
                }
            }
            else -> {}
        }
    }

    private fun onScroll(world: World, dy: Double) {
        val tl = world.require<TimelineViewComp>(entity)
        if (dy > 0) tl.zoomIn() else tl.zoomOut()
    }

    // ── 편집 작업 ────────────────────────────────────────────────────────────

    private fun togglePlay(ctx: GameContext, pb: PlaybackComp, path: String) =
        if (pb.isPlaying) pausePlayback(ctx, pb) else startPlayback(ctx, pb, path)

    private fun startPlayback(ctx: GameContext, pb: PlaybackComp, path: String) {
        if (!pb.isPlaying) {
            if (ctx.videoBackground.isPlayable()) {
                val cur = ctx.videoBackground.getTimeMs()
                val len = ctx.videoBackground.getLengthMs()
                if (len > 0L && cur >= len - 100) ctx.videoBackground.play(path)
                else ctx.videoBackground.resume()
            } else ctx.videoBackground.play(path)
            pb.isPlaying = true
        }
    }

    private fun pausePlayback(ctx: GameContext, pb: PlaybackComp) {
        if (pb.isPlaying) { ctx.videoBackground.pause(); pb.isPlaying = false; pb.isRecording = false }
    }

    private fun queueSeek(sk: SeekComp, ctx: GameContext, delta: Long) {
        sk.pendingSeekMs = (ctx.videoBackground.getSmoothTimeMs() + delta).coerceAtLeast(0L)
    }

    private fun queueSeekAbs(sk: SeekComp, absMs: Long) { sk.pendingSeekMs = absMs.coerceAtLeast(0L) }

    private fun seekEndMs(): Long =
        synchronized(notesLock) { chart.notes.maxOfOrNull { it.endTime ?: it.time } ?: 0L } + offsetMs + 2_000L

    private fun selectAll(sel: SelectionComp) {
        sel.selectedIndices.clear()
        synchronized(notesLock) { sel.selectedIndices.addAll(chart.notes.indices) }
        sel.cursorIdx = synchronized(notesLock) { chart.notes.lastIndex }
    }

    private fun navigateNote(sel: SelectionComp, dir: Int) {
        val size = synchronized(notesLock) { chart.notes.size }
        if (size == 0) return
        sel.cursorIdx = (sel.cursorIdx + dir).coerceIn(0, size - 1)
        sel.selectedIndices.clear(); sel.selectedIndices.add(sel.cursorIdx)
        val t = synchronized(notesLock) { chart.notes[sel.cursorIdx].time } + offsetMs
        ctx.videoBackground.seek(t)
    }

    private fun doUndo(ur: UndoRedoComp, sel: SelectionComp, st: EditorStateComp) {
        if (ur.undoStack.isEmpty()) return
        ur.redoStack.addLast(synchronized(notesLock) { chart.notes.map { it.copy() } })
        synchronized(notesLock) { chart.notes.clear(); chart.notes.addAll(ur.undoStack.removeLast()) }
        sel.selectedIndices.clear(); st.unsaved = true
    }

    private fun doRedo(ur: UndoRedoComp, sel: SelectionComp, st: EditorStateComp) {
        if (ur.redoStack.isEmpty()) return
        ur.undoStack.addLast(synchronized(notesLock) { chart.notes.map { it.copy() } })
        synchronized(notesLock) { chart.notes.clear(); chart.notes.addAll(ur.redoStack.removeLast()) }
        sel.selectedIndices.clear(); st.unsaved = true
    }

    private fun doCopy(sel: SelectionComp, cb: ClipboardComp) {
        if (sel.selectedIndices.isEmpty()) return
        val sorted = synchronized(notesLock) { sel.selectedIndices.sorted().map { chart.notes[it].copy() } }
        cb.baseMs = sorted.minOf { it.time }; cb.notes = sorted
    }

    private fun doDelete(sel: SelectionComp, ur: UndoRedoComp, st: EditorStateComp) {
        if (sel.selectedIndices.isEmpty()) return
        EditorUtils.saveSnapshot(chart, ur, notesLock)
        synchronized(notesLock) { sel.selectedIndices.sortedDescending().forEach { chart.notes.removeAt(it) } }
        sel.selectedIndices.clear(); sel.cursorIdx = -1; st.unsaved = true
    }

    private fun doPaste(pb: PlaybackComp, cb: ClipboardComp, q: QuantizeComp, sel: SelectionComp, ur: UndoRedoComp, st: EditorStateComp) {
        if (cb.notes.isEmpty()) return
        val cur = ctx.videoBackground.getSmoothTimeMs() - offsetMs
        val delta = cur - cb.baseMs
        EditorUtils.saveSnapshot(chart, ur, notesLock)
        val pasted = cb.notes.map { it.copy(time = EditorUtils.snapTime(it.time + delta, q, bpm), endTime = it.endTime?.let { e -> EditorUtils.snapTime(e + delta, q, bpm) }) }
        synchronized(notesLock) { chart.notes.addAll(pasted); chart.notes.sortBy { it.time } }
        sel.selectedIndices.clear()
        synchronized(notesLock) {
            pasted.forEach { p ->
                val idx = chart.notes.indexOfFirst { it.time == p.time && it.lane == p.lane && it.type == p.type }
                if (idx >= 0) sel.selectedIndices.add(idx)
            }
        }
        st.unsaved = true
    }

    private fun cycleQuantize(q: QuantizeComp) {
        val opts = listOf(4, 8, 16)
        if (!q.enabled) { q.enabled = true; q.division = 4; return }
        val idx = opts.indexOf(q.division)
        if (idx == opts.lastIndex) q.enabled = false else q.division = opts[idx + 1]
    }

    private fun save(st: EditorStateComp) {
        val c = synchronized(notesLock) { chart.toChart() }
        runCatching { ChartParser.serializeChart(c, chartFile) }.onSuccess { st.unsaved = false }
    }

    private fun toggleDecorMode(world: World, st: EditorStateComp, ds: DecorationSelectionComp) {
        st.mode = if (st.mode == EditorMode.DECOR) EditorMode.NOTE else EditorMode.DECOR
        ds.selectedIdx = -1
    }

    private fun deleteSelectedDecor(world: World, ds: DecorationSelectionComp, dec: DecorationRendererComp, st: EditorStateComp) {
        val idx = ds.selectedIdx; if (idx < 0) return
        dec.decorations.removeAt(idx)
        dec.rebuild(); ds.selectedIdx = (idx - 1).coerceAtLeast(-1); st.unsaved = true
    }

    // ── 좌표 헬퍼 ─────────────────────────────────────────────────────────────

    private fun msToScreenX(ms: Long, tl: TimelineViewComp, layout: LayoutComp): Int =
        layout.tlX + ((ms - tl.scrollMs).toDouble() / tl.visibleMs * layout.tlW).toInt()

    private fun msFromScreenX(screenX: Int, tl: TimelineViewComp, layout: LayoutComp): Long =
        tl.scrollMs + ((screenX - layout.tlX).toLong() * tl.visibleMs / layout.tlW)

    private fun clampScroll(v: Long, tl: TimelineViewComp, layout: LayoutComp): Long {
        val rangeMs = EditorUtils.getTimelineRangeMs(chart, tl.visibleMs, ctx.videoBackground.getLengthMs(), offsetMs, notesLock)
        return v.coerceIn(0L, (rangeMs - tl.visibleMs).coerceAtLeast(0L))
    }

    private fun seekOverview(world: World, mx: Int, tl: TimelineViewComp, sk: SeekComp, layout: LayoutComp, pb: PlaybackComp) {
        if (layout.tlW <= 0) return
        val ratio = ((mx - layout.tlX).toDouble() / layout.tlW).coerceIn(0.0, 1.0)
        val rangeMs = EditorUtils.getTimelineRangeMs(chart, tl.visibleMs, ctx.videoBackground.getLengthMs(), offsetMs, notesLock)
        val maxScroll = (rangeMs - tl.visibleMs).coerceAtLeast(0L)
        tl.scrollMs = (ratio * maxScroll).toLong()
    }

    private fun findNoteAt(mx: Int, my: Int, layout: LayoutComp, tl: TimelineViewComp): Int {
        val laneH = layout.noteLaneH
        return synchronized(notesLock) {
            chart.notes.indexOfFirst { note ->
                val nx = msToScreenX(note.time, tl, layout)
                if (mx < nx - 6 || mx > nx + 6) return@indexOfFirst false
                val ly = layout.laneY(note.lane)
                my >= ly && my < ly + laneH
            }
        }
    }

    private fun findDecorAt(mx: Int, my: Int, decorations: List<Decoration>, layout: LayoutComp, tl: TimelineViewComp): Int {
        val laneH = layout.decorTlH / 5
        decorations.forEachIndexed { idx, dec ->
            val lane = idx % 5; val by = layout.decorTrackY + 18 + lane * laneH
            val startPx = msToScreenX(dec.timeMs, tl, layout)
            val endPx   = msToScreenX(dec.timeMs + dec.durationMs, tl, layout)
            if (mx in startPx.coerceAtLeast(layout.tlX)..endPx.coerceAtMost(layout.tlX + layout.tlW) && my in by..(by + laneH)) return idx
        }
        return -1
    }
}
