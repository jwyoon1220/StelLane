package io.github.jwyoon1220.core.story

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class StoryMode(
    val chapters: List<StoryChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val completedChapters: Set<String> = emptySet(),
    val currentProgress: ChapterProgress? = null,
    /** 이 챕터 목록이 속한 스토리 팩의 id — [StoryProgressManager.key]로 챕터별 진행도를 팩 단위로 구분하는 데 씁니다. */
    val packId: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChapterProgress(
    val chapterId: String = "",
    val completedSongs: Set<Int> = emptySet(),
    val songScores: Map<Int, Int> = emptyMap(),
    val songAccuracies: Map<Int, Int> = emptyMap(),
    val lastPlayedSongIndex: Int = -1,
    val startTimeMs: Long = System.currentTimeMillis()
)
