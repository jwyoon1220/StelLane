package io.github.jwyoon1220.core.song

import io.github.jwyoon1220.core.data.SongEntry
import java.io.File

class SongManager(val workingDir: File) {
    private val songsDir get() = File(workingDir, "songs")

    @Volatile
    var songs: List<SongEntry> = emptyList()
        private set

    fun load() = refresh()

    fun refresh() {
        val dir = songsDir
        if (!dir.exists()) {
            dir.mkdirs()
            songs = emptyList()
            return
        }
        songs = dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { metaFile ->
                runCatching {
                    val song = ChartParser.parseSong(metaFile)
                    val songDir = File(dir, metaFile.nameWithoutExtension)
                    SongEntry(song, songDir, metaFile)
                }.getOrNull()
            }
            ?.sortedBy { it.song.title }
            ?: emptyList()
    }
}
