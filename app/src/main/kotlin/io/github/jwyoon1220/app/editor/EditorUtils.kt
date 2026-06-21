package io.github.jwyoon1220.app.editor

import io.github.jwyoon1220.app.editor.comp.QuantizeComp
import io.github.jwyoon1220.app.editor.comp.UndoRedoComp
import io.github.jwyoon1220.core.data.MutableChart
import io.github.jwyoon1220.editor.Quantizer

object EditorUtils {

    fun snapTime(ms: Long, q: QuantizeComp, bpm: Double?): Long {
        if (!q.enabled || bpm == null) return ms
        return Quantizer.snap(ms, bpm.toInt(), q.division)
    }

    fun saveSnapshot(chart: MutableChart, undoRedo: UndoRedoComp, lock: Any) {
        val snap = synchronized(lock) { chart.notes.map { it.copy() } }
        undoRedo.undoStack.addLast(snap)
        undoRedo.redoStack.clear()
        if (undoRedo.undoStack.size > undoRedo.maxHistory) undoRedo.undoStack.removeFirst()
    }

    fun getTimelineRangeMs(
        chart: MutableChart, visibleMs: Long,
        mediaLengthMs: Long, offsetMs: Long, lock: Any,
    ): Long {
        val chartEnd = synchronized(lock) { chart.notes.maxOfOrNull { it.endTime ?: it.time } ?: 0L }
        val mediaEnd = (mediaLengthMs - offsetMs).coerceAtLeast(0L)
        return maxOf(visibleMs, chartEnd + 2_000L, mediaEnd)
    }

    fun formatTime(ms: Long): String {
        val pos  = ms.coerceAtLeast(0L)
        val min  = pos / 60_000
        val sec  = (pos % 60_000) / 1000
        val msec = pos % 1000
        val ss   = if (sec < 10) "0$sec" else "$sec"
        val mmm  = when {
            msec < 10  -> "00$msec"
            msec < 100 -> "0$msec"
            else       -> "$msec"
        }
        return "$min:$ss.$mmm"
    }
}
