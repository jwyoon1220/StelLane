package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.core.story.StoryChapter
import io.github.jwyoon1220.core.story.StoryMode
import io.github.jwyoon1220.core.story.StoryProgressManager
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand

/** 챕터 완료 요약 — 곡별 점수/정확도와 언락된 보상을 보여준 뒤 챕터 선택으로 돌아갑니다. */
class StoryResultScene(
    private val ctx: GameContext,
    private val storyMode: StoryMode,
    private val chapter: StoryChapter,
    private val progressMgr: StoryProgressManager
) : Scene() {

    companion object {
        private val COLOR_BG_OVERLAY = RenderColor.of(8, 6, 16, 245)
        private val COLOR_TITLE = RenderColor.of(255, 107, 157)
        private val COLOR_CHAPTER_TITLE = RenderColor.of(255, 255, 255)
        private val COLOR_ROW_LABEL = RenderColor.of(200, 192, 220)
        private val COLOR_ROW_VALUE = RenderColor.of(59, 206, 242)
        private val COLOR_REWARD_TITLE = RenderColor.of(255, 209, 102)
        private val COLOR_REWARD_ITEM = RenderColor.of(220, 215, 240)
        private val COLOR_HINT = RenderColor.of(140, 130, 165)
    }

    private val titleFont = FontLoader.bold(30f)
    private val chapterFont = FontLoader.semiBold(26f)
    private val rowLabelFont = FontLoader.regular(18f)
    private val rowValueFont = FontLoader.semiBold(18f)
    private val rewardTitleFont = FontLoader.semiBold(18f)
    private val rewardItemFont = FontLoader.regular(16f)
    private val hintFont = FontLoader.light(14f)

    override fun enter() {
        super.enter()
        ctx.inputManager.clearEvents()
        register(ResultRenderSystem())
    }

    override fun keyPressed(key: Int, mods: Int) {
        if (key == Keys.ENTER || key == Keys.ESCAPE) {
            ctx.sceneRouter.navigate(StorySelectScene(ctx))
        }
    }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        ctx.sceneRouter.navigate(StorySelectScene(ctx))
    }

    private inner class ResultRenderSystem : RenderProducer {
        override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit
        override fun produce(world: World, out: MutableList<RenderCommand>) {
            out.add(RenderCommand.LegacyDrawContext { renderContents(this) })
        }

        private fun renderContents(g: io.github.jwyoon1220.engine.DrawContext) {
            val w = g.clipBounds.width
            val h = g.clipBounds.height
            val cx = w / 2f

            g.renderColor = COLOR_BG_OVERLAY
            g.fillRect(0, 0, w, h)

            var y = h * 0.14f

            g.font = titleFont
            g.renderColor = COLOR_TITLE
            g.drawStringCentered("CHAPTER COMPLETE", cx, y); y += 46f

            g.font = chapterFont
            g.renderColor = COLOR_CHAPTER_TITLE
            g.drawStringCentered("Chapter ${chapter.order}. ${chapter.title}", cx, y); y += 60f

            val progress = progressMgr.loadChapterProgress(chapter.id)
            chapter.requiredSongs.forEachIndexed { i, song ->
                val score = progress.songScores[i]
                val accuracy = progress.songAccuracies[i]
                val label = song.description.ifBlank { "Song ${i + 1}" }
                val value = if (score != null) "%07d  (%d%%)".format(score, accuracy ?: 0) else "-"

                g.font = rowLabelFont
                g.renderColor = COLOR_ROW_LABEL
                g.drawString(label, cx - 260f, y)

                g.font = rowValueFont
                g.renderColor = COLOR_ROW_VALUE
                g.drawStringRight(value, cx + 260f, y)
                y += 30f
            }
            y += 20f

            val rewards = chapter.rewards
            val unlockedCharacters = rewards?.unlockedCharacters.orEmpty()
            val unlockedEmojis = rewards?.unlockedEmojis.orEmpty()
            if (unlockedCharacters.isNotEmpty() || unlockedEmojis.isNotEmpty()) {
                g.font = rewardTitleFont
                g.renderColor = COLOR_REWARD_TITLE
                g.drawStringCentered("NEW UNLOCKED", cx, y); y += 28f

                g.font = rewardItemFont
                g.renderColor = COLOR_REWARD_ITEM
                val items = (unlockedCharacters + unlockedEmojis).joinToString("   ")
                g.drawStringCentered(items, cx, y); y += 30f
            }

            val hasNext = storyMode.chapters.indexOfFirst { it.id == chapter.id }
                .let { it >= 0 && it + 1 < storyMode.chapters.size }
            g.font = hintFont
            g.renderColor = COLOR_HINT
            val hint = if (hasNext) "Enter : 챕터 선택으로 (다음 챕터가 열렸습니다)" else "Enter : 챕터 선택으로"
            g.drawStringCentered(hint, cx, h - 40f)
        }
    }
}
