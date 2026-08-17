package io.github.jwyoon1220.core.story

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * `<workingDir>/story/` 아래의 스토리 팩 폴더들을 스캔해 로드합니다 — [io.github.jwyoon1220.core.song.SongManager]와
 * 동일한 패턴(작업 디렉토리 하위 폴더를 런타임에 스캔)입니다.
 *
 * 팩 하나가 폴더 하나입니다(`story/<packId>/`): 폴더 루트의 `pack.json`이 팩 메타데이터(제목/설명/순서)를,
 * `chapters/` 하위 JSON 파일들이 그 팩의 챕터를 담습니다. 초상화/배경 이미지는 팩 하위의 `images/`,
 * 컷신 배경 영상은 `videos/`에서 로드합니다 — 팩끼리 공유하지 않으므로 서로 다른 주제의 스토리를 파일명
 * 충돌 없이 독립적으로 만들 수 있습니다. 유저는 이 폴더 구조 그대로 새 팩을 직접 추가하거나,
 * 게임 내 스토리 에디터로 만들 수 있습니다.
 */
class StoryManager(val workingDir: File) {
    val storyDir: File get() = File(workingDir, "story")

    @Volatile
    var packs: List<LoadedStoryPack> = emptyList()
        private set

    private val mapper = jacksonObjectMapper()

    fun load() = refresh()

    fun refresh() {
        val dir = storyDir
        if (!dir.exists()) {
            dir.mkdirs()
            packs = emptyList()
            return
        }
        packs = dir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { packDir -> loadPack(packDir) }
            ?.sortedBy { it.pack.order }
            ?: emptyList()
    }

    private fun loadPack(packDir: File): LoadedStoryPack? {
        val metaFile = File(packDir, "pack.json")
        if (!metaFile.isFile) return null
        val meta = runCatching { mapper.readValue(metaFile, StoryPack::class.java) }.getOrNull() ?: return null
        val pack = if (meta.id.isBlank()) meta.copy(id = packDir.name) else meta
        val chaptersDir = File(packDir, "chapters")
        val chapters = chaptersDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { file -> runCatching { mapper.readValue(file, StoryChapter::class.java) }.getOrNull() }
            ?.sortedBy { it.order }
            ?: emptyList()
        return LoadedStoryPack(pack = pack, dir = packDir, chapters = chapters)
    }
}
