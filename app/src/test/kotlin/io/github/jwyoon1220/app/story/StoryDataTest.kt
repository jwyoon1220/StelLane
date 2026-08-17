package io.github.jwyoon1220.app.story

import io.github.jwyoon1220.core.story.LoadedStoryPack
import io.github.jwyoon1220.core.story.StoryManager
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 기본 제공 스토리 팩(`assets/src/main/resources/story/sonata_forgotten/`)이 올바르게 파싱되고,
 * 참조하는 캐릭터/배경 이미지가 실제로 존재하는지 검증합니다.
 * (배포 시 `assets/story/`가 `run/story/`로 복사되며, [StoryManager]는 그 폴더 아래 팩들을 런타임에 스캔합니다.)
 */
class StoryDataTest {

    private fun loadDefaultPack(): LoadedStoryPack {
        val storyManager = StoryManager(File("../assets/src/main/resources")).apply { load() }
        return storyManager.packs.firstOrNull { it.pack.id == "sonata_forgotten" }
            ?: error("sonata_forgotten 팩을 찾을 수 없습니다: ${storyManager.packs.map { it.pack.id }}")
    }

    @Test
    fun `기본 스토리 팩이 6개 챕터를 포함한다`() {
        val chapters = loadDefaultPack().chapters
        assertEquals(6, chapters.size, "챕터는 6개여야 합니다")
        chapters.forEachIndexed { i, chapter ->
            assertTrue(chapter.id.isNotBlank(), "챕터 id가 비어있음 (index=$i)")
            assertTrue(chapter.title.isNotBlank(), "챕터 title이 비어있음 (index=$i)")
            assertTrue(chapter.requiredSongs.isNotEmpty(), "챕터에 곡이 없음: ${chapter.id}")
            assertTrue(chapter.cutscenes.isNotEmpty(), "챕터에 커트신이 없음: ${chapter.id}")
        }
    }

    @Test
    fun `모든 커트신이 참조하는 캐릭터-배경 이미지가 팩의 images 폴더에 존재한다`() {
        val pack = loadDefaultPack()
        val referenced = LinkedHashSet<String>()
        for (chapter in pack.chapters) {
            for (scene in chapter.cutscenes) {
                scene.backgroundImage?.let { referenced.add(it) }
                for (line in scene.dialogues) {
                    line.characterImage?.let { referenced.add(it) }
                }
            }
        }
        assertTrue(referenced.isNotEmpty(), "참조된 이미지가 하나도 없습니다")
        for (imageName in referenced) {
            val file = File(pack.imagesDir, imageName)
            assertTrue(file.isFile, "이미지 파일을 찾을 수 없음: ${file.path}")
        }
    }

    @Test
    fun `모든 커트신 timing이 before_song_N-after_song_N 형식과 유효한 곡 인덱스를 따른다`() {
        val chapters = loadDefaultPack().chapters
        val timingRegex = Regex("^(before|after)_song_(\\d+)$")
        for (chapter in chapters) {
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
        val chapters = loadDefaultPack().chapters
        for (chapter in chapters) {
            for (scene in chapter.cutscenes) {
                for (line in scene.dialogues) {
                    assertTrue(line.character.isNotBlank(), "화자가 비어있음: ${chapter.id}/${scene.id}")
                    assertTrue(line.text.isNotBlank(), "대사가 비어있음: ${chapter.id}/${scene.id}")
                }
            }
        }
    }
}
