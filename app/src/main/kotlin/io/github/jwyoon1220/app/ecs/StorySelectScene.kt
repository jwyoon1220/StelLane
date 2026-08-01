package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.story.StoryFlowController
import io.github.jwyoon1220.core.story.StoryChapter
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand
import org.slf4j.LoggerFactory

/** 스토리 모드 챕터 선택 화면 — 완료/진행가능/잠김 상태를 표시하고, 선택 시 [StoryFlowController]를 시작합니다. */
class StorySelectScene(private val ctx: GameContext) : Scene() {

    companion object {
        private val COLOR_BG = RenderColor.of(8, 6, 16)
        private val COLOR_TITLE = RenderColor.of(255, 255, 255)
        private val COLOR_SUBTITLE = RenderColor.of(163, 112, 247, 220)
        private val COLOR_ROW_BG_SEL = RenderColor.of(255, 107, 157, 35)
        private val COLOR_ROW_BG_HOVER = RenderColor.of(59, 206, 242, 20)
        private val COLOR_ROW_BORDER_SEL = RenderColor.of(255, 107, 157, 200)
        private val COLOR_ROW_BORDER = RenderColor.of(70, 55, 100, 140)
        private val COLOR_CHAPTER_TITLE_UNLOCKED = RenderColor.of(255, 255, 255)
        private val COLOR_CHAPTER_TITLE_LOCKED = RenderColor.of(110, 100, 130)
        private val COLOR_CHAPTER_DESC = RenderColor.of(180, 172, 200)
        private val COLOR_CHAPTER_DESC_LOCKED = RenderColor.of(90, 82, 105)
        private val COLOR_STATUS_DONE = RenderColor.of(120, 220, 150)
        private val COLOR_STATUS_READY = RenderColor.of(255, 209, 102)
        private val COLOR_STATUS_LOCKED = RenderColor.of(120, 110, 140)
        private val COLOR_HINT = RenderColor.of(140, 130, 165)
    }

    private val log = LoggerFactory.getLogger(StorySelectScene::class.java)

    private val titleFont = FontLoader.bold(40f)
    private val subFont = FontLoader.extraLight(15f)
    private val chapterTitleFont = FontLoader.semiBold(24f)
    private val descFont = FontLoader.regular(15f)
    private val statusFont = FontLoader.semiBold(14f)
    private val hintFont = FontLoader.light(13f)

    private var selectedIndex = 0
    private var hoverIndex = -1

    private val rowX = 100f
    private val rowW = 1080f
    private val rowH = 84f
    private val listStartY = 150f

    override fun enter() {
        super.enter()
        ctx.inputManager.clearEvents()
        selectedIndex = ctx.storyProgressMgr.loadStoryMode(ctx.storyModeData).currentChapterIndex.coerceIn(0, maxOf(0, chapters().size - 1))
        register(StorySelectRenderSystem())
    }

    private fun chapters(): List<StoryChapter> = ctx.storyModeData.chapters

    override fun keyPressed(key: Int, mods: Int) {
        val list = chapters()
        if (list.isEmpty()) {
            if (key == Keys.ESCAPE) ctx.sceneRouter.navigate(MainMenuScene(ctx))
            return
        }
        when (key) {
            Keys.UP -> selectedIndex = (selectedIndex - 1 + list.size) % list.size
            Keys.DOWN -> selectedIndex = (selectedIndex + 1) % list.size
            Keys.ENTER -> onSelect()
            Keys.ESCAPE -> ctx.sceneRouter.navigate(MainMenuScene(ctx))
        }
    }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        updateHover(x, y)
        if (hoverIndex >= 0) {
            selectedIndex = hoverIndex
            onSelect()
        }
    }

    override fun mouseDragged(x: Float, y: Float, button: Int) = updateHover(x, y)
    override fun mousePressed(x: Float, y: Float, button: Int, mods: Int) = updateHover(x, y)

    private fun updateHover(x: Float, y: Float) {
        hoverIndex = -1
        chapters().forEachIndexed { i, _ ->
            val ry = listStartY + i * (rowH + 16f)
            if (x in rowX..(rowX + rowW) && y in ry..(ry + rowH)) hoverIndex = i
        }
    }

    private fun onSelect() {
        val chapter = chapters().getOrNull(selectedIndex) ?: return
        if (!ctx.storyProgressMgr.isChapterUnlocked(ctx.storyModeData, chapter.id)) {
            log.info("잠긴 챕터 선택 시도: {}", chapter.id)
            return
        }
        log.info("스토리 챕터 시작: {} ({})", chapter.title, chapter.id)
        StoryFlowController(ctx, ctx.storyModeData, chapter, ctx.storyProgressMgr).start()
    }

    private inner class StorySelectRenderSystem : RenderProducer {
        override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit
        override fun produce(world: World, out: MutableList<RenderCommand>) {
            out.add(RenderCommand.LegacyDrawContext { renderContents(this) })
        }

        private fun renderContents(g: io.github.jwyoon1220.engine.DrawContext) {
            val w = g.clipBounds.width
            val h = g.clipBounds.height

            g.renderColor = COLOR_BG
            g.fillRect(0, 0, w, h)

            g.font = titleFont
            g.renderColor = COLOR_TITLE
            g.drawString("Story Mode", rowX, 70f)
            g.font = subFont
            g.renderColor = COLOR_SUBTITLE
            g.drawString("Sonata for the Forgotten", rowX, 92f)

            val list = chapters()
            if (list.isEmpty()) {
                g.font = descFont
                g.renderColor = COLOR_CHAPTER_DESC
                g.drawString("스토리 데이터를 찾을 수 없습니다.", rowX, listStartY)
                return
            }

            list.forEachIndexed { i, chapter ->
                val ry = listStartY + i * (rowH + 16f)
                val selected = i == selectedIndex
                val hovered = i == hoverIndex
                val unlocked = ctx.storyProgressMgr.isChapterUnlocked(ctx.storyModeData, chapter.id)
                val completed = ctx.storyProgressMgr.isChapterCompleted(chapter.id)

                if (selected) {
                    g.renderColor = COLOR_ROW_BG_SEL
                    g.fillRoundRect(rowX, ry, rowW, rowH, 10f)
                } else if (hovered) {
                    g.renderColor = COLOR_ROW_BG_HOVER
                    g.fillRoundRect(rowX, ry, rowW, rowH, 10f)
                }
                g.renderColor = if (selected) COLOR_ROW_BORDER_SEL else COLOR_ROW_BORDER
                g.drawRoundRect(rowX, ry, rowW, rowH, 10f)

                g.font = chapterTitleFont
                g.renderColor = if (unlocked) COLOR_CHAPTER_TITLE_UNLOCKED else COLOR_CHAPTER_TITLE_LOCKED
                val titleText = "Chapter ${chapter.order}. ${chapter.title}"
                g.drawString(titleText, rowX + 24f, ry + 32f)

                g.font = descFont
                g.renderColor = if (unlocked) COLOR_CHAPTER_DESC else COLOR_CHAPTER_DESC_LOCKED
                val descText = if (unlocked) chapter.description else "이전 챕터를 완료하면 열립니다."
                g.drawString(truncate(g, descText, rowW - 220f), rowX + 24f, ry + 58f)

                val (statusText, statusColor) = when {
                    completed -> "완료" to COLOR_STATUS_DONE
                    unlocked -> "플레이 가능" to COLOR_STATUS_READY
                    else -> "잠김" to COLOR_STATUS_LOCKED
                }
                g.font = statusFont
                g.renderColor = statusColor
                g.drawStringRight(statusText, rowX + rowW - 24f, ry + 46f)
            }

            g.font = hintFont
            g.renderColor = COLOR_HINT
            g.drawString("↑↓ 탐색    Enter 선택    ESC 뒤로", rowX, h - 24f)
        }

        private fun truncate(g: io.github.jwyoon1220.engine.DrawContext, text: String, maxWidth: Float): String {
            if (g.measureStringWidth(text) <= maxWidth) return text
            var s = text
            while (s.isNotEmpty() && g.measureStringWidth("$s…") > maxWidth) {
                s = s.dropLast(1)
            }
            return "$s…"
        }
    }
}
