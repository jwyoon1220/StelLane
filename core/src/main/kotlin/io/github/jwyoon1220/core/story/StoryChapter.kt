package io.github.jwyoon1220.core.story

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class StoryChapter(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val order: Int = 0,
    val cutscenes: List<StoryScene> = emptyList(),
    val requiredSongs: List<ChapterSong> = emptyList(),
    val rewards: ChapterReward? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChapterSong(
    val songEntryId: String = "",
    val difficulty: String = "Normal",
    val minAccuracy: Int = 0,
    val description: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StoryScene(
    val id: String = "",
    val timing: String = "",
    val backgroundImage: String? = null,
    /** 배경 영상(`story/videos/` 기준 파일명). 설정되면 [backgroundImage]보다 우선하며, 끝까지 재생되면 반복 재생됩니다. */
    val backgroundVideo: String? = null,
    val dialogues: List<DialogueLine> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DialogueLine(
    val character: String = "",
    val characterImage: String? = null,
    val position: String = "center",
    val emotion: String? = null,
    val text: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChapterReward(
    val unlockNextChapter: Boolean = true,
    val unlockedCharacters: List<String> = emptyList(),
    val unlockedEmojis: List<String> = emptyList()
)
