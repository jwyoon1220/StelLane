package io.github.jwyoon1220.editor.sys

import io.github.jwyoon1220.editor.EditorUtils
import io.github.jwyoon1220.editor.comp.*
import io.github.jwyoon1220.core.data.MutableChart
import io.github.jwyoon1220.core.data.MutableNote
import io.github.jwyoon1220.core.data.NoteType
import io.github.jwyoon1220.engine.ecs.EcsSystem
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.World

class RecordingSystem(
    private val entity: Long,
    private val chart: MutableChart,
    private val notesLock: Any,
    private val bpm: Double?,
) : EcsSystem {

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) {
        val pb  = world.require<PlaybackComp>(entity)
        val rec = world.require<RecordingComp>(entity)
        val q   = world.require<QuantizeComp>(entity)
        val st  = world.require<EditorStateComp>(entity)
        val ur  = world.require<UndoRedoComp>(entity)

        if (!pb.isRecording || !pb.isPlaying) return
        val now = pb.currentTimeMs

        for (ev in input.laneEvents) {
            val lane = ev.lane
            if (ev.pressed) {
                rec.heldLaneStartMs[lane] = now
            } else {
                val start = rec.heldLaneStartMs[lane]
                if (start < 0) continue
                val dur     = now - start
                val snapped = EditorUtils.snapTime(start, q, bpm)

                EditorUtils.saveSnapshot(chart, ur, notesLock)
                synchronized(notesLock) {
                    if (dur < 150 || st.noteInputMode == NoteInputMode.NORMAL) {
                        chart.notes.add(MutableNote(snapped, lane, NoteType.SHORT))
                    } else {
                        chart.notes.add(MutableNote(snapped, lane, NoteType.LONG, EditorUtils.snapTime(now, q, bpm)))
                    }
                    chart.notes.sortBy { it.time }
                }
                rec.heldLaneStartMs[lane] = -1L
                st.unsaved = true
            }
        }
    }
}
