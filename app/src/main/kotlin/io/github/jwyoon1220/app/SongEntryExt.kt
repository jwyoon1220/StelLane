package io.github.jwyoon1220.app

import io.github.jwyoon1220.core.data.SongEntry
import java.io.File

/** 미디어 파일 절대 경로를 반환합니다. video → audio 우선순위로 탐색하며, 없으면 null. */
fun SongEntry.resolveMediaPath(): String? = when {
    song.videoPath != null -> File(songDir, song.videoPath).absolutePath
    song.audioPath != null -> File(songDir, song.audioPath).absolutePath
    else                   -> null
}
