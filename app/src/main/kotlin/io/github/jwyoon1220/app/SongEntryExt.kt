package io.github.jwyoon1220.app

import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.engine.multiplayer.MultiplayerCacheManager
import io.github.jwyoon1220.engine.multiplayer.proto.FileEntry
import java.io.File

/** 미디어 파일 절대 경로를 반환합니다. video → audio 우선순위로 탐색하며, 없으면 null. */
fun SongEntry.resolveMediaPath(): String? = when {
    song.videoPath != null -> File(songDir, song.videoPath).absolutePath
    song.audioPath != null -> File(songDir, song.audioPath).absolutePath
    else                   -> null
}

/**
 * 멀티플레이어 클라이언트용 미디어 경로 해석. 호스트로부터 전송받아 캐시된 파일을 우선 사용하고,
 * (동일 곡 폴더를 로컬에도 가지고 있어) 캐시가 없으면 로컬 songDir의 파일로 폴백합니다.
 */
fun SongEntry.resolveMultiplayerMediaPath(files: List<FileEntry>): String? {
    val relPath = song.videoPath ?: song.audioPath ?: return null
    val fileEntry = files.firstOrNull { it.relPath == relPath }
    val cachedPath = fileEntry?.let { MultiplayerCacheManager.getCachedPath(it.sha256) }
    return cachedPath?.toString() ?: File(songDir, relPath).takeIf { it.exists() }?.absolutePath
}
