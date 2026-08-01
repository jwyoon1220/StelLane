package io.github.jwyoon1220.core.story

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** 클래스패스 리소스(`/story/story_data.json`)에서 스토리 데이터를 로드합니다. */
object StoryDataLoader {
    private val mapper = jacksonObjectMapper()

    fun loadFromResources(resourcePath: String = "/story/story_data.json"): StoryMode {
        val stream = StoryDataLoader::class.java.getResourceAsStream(resourcePath)
            ?: return StoryMode()
        return stream.use { mapper.readValue(it, StoryMode::class.java) }
    }
}
