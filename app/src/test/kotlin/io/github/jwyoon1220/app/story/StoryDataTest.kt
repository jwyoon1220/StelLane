package io.github.jwyoon1220.app.story

import io.github.jwyoon1220.core.story.StoryDataLoader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** story_data.json이 올바르게 파싱되고, 참조하는 캐릭터/배경 이미지가 실제로 존재하는지 검증합니다. */
class StoryDataTest {

    @Test
    fun `스토리 데이터가 6개 챕터를 포함한다`() {
        val storyMode = StoryDataLoader.loadFromResources()
        assertEquals(6, storyMode.chapters.size, "챕터는 6개여야 합니다")
        storyMode.chapters.forEachIndexed { i, chapter ->
            assertTrue(chapter.id.isNotBlank(), "챕터 id가 비어있음 (index=$i)")
            assertTrue(chapter.title.isNotBlank(), "챕터 title이 비어있음 (index=$i)")
            assertTrue(chapter.requiredSongs.isNotEmpty(), "챕터에 곡이 없음: ${chapter.id}")
            assertTrue(chapter.cutscenes.isNotEmpty(), "챕터에 커트신이 없음: ${chapter.id}")
        }
    }

    @Test
    fun `모든 커트신이 참조하는 캐릭터-배경 이미지가 classpath에 존재한다`() {
        val storyMode = StoryDataLoader.loadFromResources()
        val referenced = LinkedHashSet<String>()
        for (chapter in storyMode.chapters) {
            for (scene in chapter.cutscenes) {
                scene.backgroundImage?.let { referenced.add(it) }
                for (line in scene.dialogues) {
                    line.characterImage?.let { referenced.add(it) }
                }
            }
        }
        assertTrue(referenced.isNotEmpty(), "참조된 이미지가 하나도 없습니다")
        for (imageName in referenced) {
            val stream = StoryDataTest::class.java.getResourceAsStream("/story/$imageName")
            assertTrue(stream != null, "이미지 리소스를 찾을 수 없음: /story/$imageName")
            stream?.close()
        }
    }

    @Test
    fun `모든 커트신 timing이 before_song_N-after_song_N 형식과 유효한 곡 인덱스를 따른다`() {
        val storyMode = StoryDataLoader.loadFromResources()
        val timingRegex = Regex("^(before|after)_song_(\\d+)$")
        for (chapter in storyMode.chapters) {
            for (scene in chapter.cutscenes) {
                val match = timingRegex.matchEntire(scene.timing)
                assertTrue(match != null, "잘못된 timing 형식: ${chapter.id}/${scene.id} -> '${scene.timing}'")
                val songIndex = match!!.groupValues[2].toInt()
                assertTrue(
                    songIndex in chapter.requiredSongs.indices,
                    "timing이 가리키는 곡 인덱스가 범위 밖: ${chapter.id}/${scene.id} -> songIndex=$songIndex, songCount=${chapter.requiredSongs.size}"
                )
            }
        }
    }

    @Test
    fun `모든 대사에 화자와 본문이 있다`() {
        val storyMode = StoryDataLoader.loadFromResources()
        for (chapter in storyMode.chapters) {
            for (scene in chapter.cutscenes) {
                for (line in scene.dialogues) {
                    assertTrue(line.character.isNotBlank(), "화자가 비어있음: ${chapter.id}/${scene.id}")
                    assertTrue(line.text.isNotBlank(), "대사가 비어있음: ${chapter.id}/${scene.id}")
                }
            }
        }
    }
}
