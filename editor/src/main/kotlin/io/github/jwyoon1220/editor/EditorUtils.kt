package io.github.jwyoon1220.editor

import io.github.jwyoon1220.editor.comp.QuantizeComp
import io.github.jwyoon1220.editor.comp.UndoRedoComp
import io.github.jwyoon1220.core.data.MutableChart
import io.github.jwyoon1220.core.data.MutableNote
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

    /**
     * notes가 time 오름차순 정렬되어 있다는 전제 하에(추가/붙여넣기/녹음 직후 항상 재정렬됨),
     * time >= target인 첫 인덱스를 이진탐색으로 찾습니다.
     */
    fun lowerBoundByTime(notes: List<MutableNote>, target: Long): Int {
        var lo = 0; var hi = notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].time < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 위와 같은 전제 하에, time > target인 첫 인덱스(=마지막으로 포함할 인덱스 + 1)를 찾습니다. */
    fun upperBoundByTime(notes: List<MutableNote>, target: Long): Int {
        var lo = 0; var hi = notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].time <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun formatTime(ms: Long): String {
        val pos  = ms.coerceAtLeast(0L)
        val min  = pos / 60_000
        val sec  = (pos % 60_000) / 1000
        val msec = pos % 1000
        return "%d:%02d.%03d".format(min, sec, msec)
    }
}
