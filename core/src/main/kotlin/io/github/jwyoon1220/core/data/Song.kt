package io.github.jwyoon1220.core.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.io.File

// JSON으로 직렬화되는 곡 메타데이터
@JsonIgnoreProperties(ignoreUnknown = true)
data class Song(
    val title: String = "",
    val artist: String = "",
    val bpm: Int? = null,
    val coverImagePath: String? = null,
    val videoPath: String? = null,
    val audioPath: String? = null,
    val difficulties: Map<String, String> = emptyMap()
)

// 런타임에 사용하는 곡 정보 (파일 경로 포함)
data class SongEntry(
    val song: Song,
    val songDir: File,      // 리소스 서브폴더 (cover, video 등)
    val metaFile: File      // *.json 메타파일 경로
)
