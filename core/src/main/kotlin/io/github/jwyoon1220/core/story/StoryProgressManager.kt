package io.github.jwyoon1220.core.story

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.prefs.Preferences

/** 스토리 모드 진행도 저장/로드 — java.util.prefs 를 통해 OS 레지스트리/파일에 저장됩니다. */
class StoryProgressManager {
    companion object {
        private const val KEY_CURRENT_CHAPTER = "story_current_chapter"
        private const val KEY_COMPLETED_CHAPTERS = "story_completed_chapters"
        private const val KEY_PROGRESS_PREFIX = "story_progress_"
    }

    private val prefs: Preferences = Preferences.userNodeForPackage(StoryProgressManager::class.java)
    private val mapper = jacksonObjectMapper()

    /**
     * 팩 id와 챕터 id를 하나의 진행도 키로 합칩니다. 서로 다른 팩이 같은 챕터 id를 써도 진행도가
     * 섞이지 않도록, [isChapterCompleted]/[completeChapter]/[loadChapterProgress]/[saveChapterProgress]/
     * [currentChapterId]에는 챕터 id 대신 이 함수로 만든 키를 넘겨야 합니다.
     */
    fun key(packId: String, chapterId: String): String = "$packId:$chapterId"

    var currentChapterId: String
        get() = prefs.get(KEY_CURRENT_CHAPTER, "")
        set(v) { prefs.put(KEY_CURRENT_CHAPTER, v) }

    fun loadCompletedChapters(): Set<String> {
        val raw = prefs.get(KEY_COMPLETED_CHAPTERS, "")
        if (raw.isBlank()) return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun isChapterCompleted(chapterId: String): Boolean =
        chapterId in loadCompletedChapters()

    fun completeChapter(chapterId: String) {
        val completed = loadCompletedChapters().toMutableSet()
        completed.add(chapterId)
        prefs.put(KEY_COMPLETED_CHAPTERS, completed.joinToString(","))
    }

    fun loadChapterProgress(chapterId: String): ChapterProgress {
        val json = prefs.get(KEY_PROGRESS_PREFIX + chapterId, null)
            ?: return ChapterProgress(chapterId = chapterId)
        return try {
            mapper.readValue(json, ChapterProgress::class.java)
        } catch (e: Exception) {
            ChapterProgress(chapterId = chapterId)
        }
    }

    fun saveChapterProgress(progress: ChapterProgress) {
        val json = mapper.writeValueAsString(progress)
        prefs.put(KEY_PROGRESS_PREFIX + progress.chapterId, json)
        currentChapterId = progress.chapterId
    }

    /** [storyMode] 챕터 순서를 기준으로 [chapterId] 다음 챕터를 반환합니다 (없으면 null). */
    fun nextChapter(storyMode: StoryMode, chapterId: String): StoryChapter? {
        val idx = storyMode.chapters.indexOfFirst { it.id == chapterId }
        if (idx < 0 || idx + 1 >= storyMode.chapters.size) return null
        return storyMode.chapters[idx + 1]
    }

    /** [storyMode] 안에서 [chapterId]가 플레이 가능한지 (첫 챕터이거나 이전 챕터가 완료됨). */
    fun isChapterUnlocked(storyMode: StoryMode, chapterId: String): Boolean {
        val idx = storyMode.chapters.indexOfFirst { it.id == chapterId }
        if (idx <= 0) return true
        val prevChapter = storyMode.chapters[idx - 1]
        return isChapterCompleted(key(storyMode.packId, prevChapter.id))
    }

    fun loadStoryMode(baseStoryMode: StoryMode): StoryMode {
        val completed = loadCompletedChapters()
        val currentId = currentChapterId
        val currentIndex = if (currentId.isNotEmpty()) {
            baseStoryMode.chapters.indexOfFirst { key(baseStoryMode.packId, it.id) == currentId }.takeIf { it >= 0 } ?: 0
        } else 0
        val progress = if (currentId.isNotEmpty()) loadChapterProgress(currentId) else null

        return baseStoryMode.copy(
            currentChapterIndex = currentIndex,
            completedChapters = completed,
            currentProgress = progress
        )
    }
}
