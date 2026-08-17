package io.github.jwyoon1220.core.story

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.io.File

/** 스토리 팩(주제별 스토리 묶음) 폴더 루트의 `pack.json`에 저장되는 메타데이터. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StoryPack(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val order: Int = 0
)

/**
 * [StoryManager]가 런타임에 조립한, 챕터까지 포함된 완전한 스토리 팩.
 * 팩 하나가 폴더 하나([dir])이며, 이미지/영상은 팩끼리 공유하지 않고 [imagesDir]/[videosDir]에서 각자 로드합니다.
 */
data class LoadedStoryPack(
    val pack: StoryPack,
    val dir: File,
    val chapters: List<StoryChapter>
) {
    val imagesDir: File get() = File(dir, "images")
    val videosDir: File get() = File(dir, "videos")
}
