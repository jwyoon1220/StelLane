package io.github.jwyoon1220.app.story

import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.ecs.PlayScene
import io.github.jwyoon1220.app.ecs.StoryResultScene
import io.github.jwyoon1220.app.ecs.StorySelectScene
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.song.ChartParser
import io.github.jwyoon1220.core.story.ChapterSong
import io.github.jwyoon1220.core.story.StoryChapter
import io.github.jwyoon1220.core.story.StoryMode
import io.github.jwyoon1220.core.story.StoryProgressManager
import io.github.jwyoon1220.core.story.StoryScene
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 챕터 하나(커트신 + 여러 곡)를 순서대로 진행시키는 오케스트레이터.
 * 그 자체는 Scene이 아니라, [StoryCutsceneScene]과 기존 [PlayScene] 사이의 내비게이션을 조립합니다.
 * PlayScene은 수정 없이 그대로 재사용하며, [PlayScene.onExit]/[PlayScene.onResultConfirmed] 훅으로
 * 스토리 흐름에 맞게 내비게이션만 가로챕니다.
 */
class StoryFlowController(
    private val ctx: GameContext,
    private val storyMode: StoryMode,
    private val chapter: StoryChapter,
    private val progressMgr: StoryProgressManager
) {
    private val log = LoggerFactory.getLogger(StoryFlowController::class.java)

    private sealed interface Step {
        data class Cutscene(val scene: StoryScene) : Step
        data class Song(val chapterSong: ChapterSong, val index: Int) : Step
    }

    private val steps: List<Step> = buildSteps()
    private var stepIndex = 0

    /** 챕터의 곡 시퀀스를 순회하며 "before_song_N"/"after_song_N" 타이밍의 커트신을 그 사이에 끼워 넣습니다. */
    private fun buildSteps(): List<Step> {
        val result = ArrayList<Step>()
        val songs = chapter.requiredSongs
        for (i in songs.indices) {
            chapter.cutscenes.firstOrNull { it.timing == "before_song_$i" }?.let { result.add(Step.Cutscene(it)) }
            result.add(Step.Song(songs[i], i))
            chapter.cutscenes.firstOrNull { it.timing == "after_song_$i" }?.let { result.add(Step.Cutscene(it)) }
        }
        return result
    }

    /** 챕터의 첫 스텝부터 진행을 시작합니다. */
    fun start() {
        stepIndex = 0
        progressMgr.currentChapterId = chapter.id
        advance()
    }

    private fun advance() {
        if (stepIndex >= steps.size) {
            finishChapter()
            return
        }
        when (val step = steps[stepIndex]) {
            is Step.Cutscene -> ctx.sceneRouter.navigate(
                StoryCutsceneScene(ctx, step.scene) { stepIndex++; advance() }
            )
            is Step.Song -> playSong(step)
        }
    }

    private fun playSong(step: Step.Song) {
        val songEntry = findSongEntry(step.chapterSong.songEntryId)
        val chartFileName = songEntry?.song?.difficulties?.get(step.chapterSong.difficulty)
            ?: songEntry?.song?.difficulties?.values?.firstOrNull()

        if (songEntry == null || chartFileName == null) {
            log.warn(
                "스토리 곡을 찾을 수 없어 스텝을 건너뜁니다: songId={}, difficulty={}",
                step.chapterSong.songEntryId, step.chapterSong.difficulty
            )
            stepIndex++
            advance()
            return
        }

        val chart = ChartParser.parseChart(File(songEntry.songDir, chartFileName))
        val playScene = PlayScene(ctx, songEntry, chart)
        playScene.onExit = { ctx.sceneRouter.navigate(StorySelectScene(ctx)) }
        playScene.onResultConfirmed = {
            recordSongResult(playScene, step.index)
            stepIndex++
            advance()
        }
        ctx.sceneRouter.navigate(playScene)
    }

    private fun recordSongResult(playScene: PlayScene, songIndex: Int) {
        val counts = playScene.scoreEngine.counts
        val accuracy = PlayScene.computeAccuracy(counts[0], counts[1], counts[2], counts[3], defaultIfEmpty = 0.0)
        val progress = progressMgr.loadChapterProgress(chapter.id)
        val updated = progress.copy(
            completedSongs = progress.completedSongs + songIndex,
            songScores = progress.songScores + (songIndex to playScene.scoreEngine.score),
            songAccuracies = progress.songAccuracies + (songIndex to accuracy.toInt()),
            lastPlayedSongIndex = songIndex
        )
        progressMgr.saveChapterProgress(updated)
    }

    private fun finishChapter() {
        progressMgr.completeChapter(chapter.id)
        ctx.sceneRouter.navigate(StoryResultScene(ctx, storyMode, chapter, progressMgr))
    }

    private fun findSongEntry(songEntryId: String): SongEntry? =
        ctx.songManager.songs.firstOrNull { it.songDir.name == songEntryId }
}
