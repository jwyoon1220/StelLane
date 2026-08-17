package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.core.story.LoadedStoryPack
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand
import org.slf4j.LoggerFactory

/**
 * 스토리 팩(주제별 스토리 묶음) 선택 화면 — `story/` 아래 각 폴더가 팩 하나입니다.
 * 팩을 고르면 그 팩의 챕터 목록([StorySelectScene])으로 진입합니다.
 */
class StoryPackSelectScene(private val ctx: GameContext) : Scene() {

    companion object {
        private val COLOR_BG = RenderColor.of(8, 6, 16)
        private val COLOR_TITLE = RenderColor.of(255, 255, 255)
        private val COLOR_SUBTITLE = RenderColor.of(163, 112, 247, 220)
        private val COLOR_ROW_BG_SEL = RenderColor.of(255, 107, 157, 35)
        private val COLOR_ROW_BG_HOVER = RenderColor.of(59, 206, 242, 20)
        private val COLOR_ROW_BORDER_SEL = RenderColor.of(255, 107, 157, 200)
        private val COLOR_ROW_BORDER = RenderColor.of(70, 55, 100, 140)
        private val COLOR_PACK_TITLE = RenderColor.of(255, 255, 255)
        private val COLOR_PACK_DESC = RenderColor.of(180, 172, 200)
        private val COLOR_PACK_COUNT = RenderColor.of(140, 130, 165)
        private val COLOR_HINT = RenderColor.of(140, 130, 165)
    }

    private val log = LoggerFactory.getLogger(StoryPackSelectScene::class.java)

    private val titleFont = FontLoader.bold(40f)
    private val subFont = FontLoader.extraLight(15f)
    private val packTitleFont = FontLoader.semiBold(24f)
    private val descFont = FontLoader.regular(15f)
    private val countFont = FontLoader.semiBold(14f)
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
        // 에디터로 새 팩을 만들고 게임으로 돌아왔을 수 있으므로 매번 다시 스캔합니다.
        ctx.storyManager.refresh()
        selectedIndex = selectedIndex.coerceIn(0, maxOf(0, packs().size - 1))
        register(PackSelectRenderSystem())
    }

    private fun packs(): List<LoadedStoryPack> = ctx.storyManager.packs

    override fun keyPressed(key: Int, mods: Int) {
        val list = packs()
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
        packs().forEachIndexed { i, _ ->
            val ry = listStartY + i * (rowH + 16f)
            if (x in rowX..(rowX + rowW) && y in ry..(ry + rowH)) hoverIndex = i
        }
    }

    private fun onSelect() {
        val pack = packs().getOrNull(selectedIndex) ?: return
        log.info("스토리 팩 선택: {} ({})", pack.pack.title, pack.pack.id)
        ctx.sceneRouter.navigate(StorySelectScene(ctx, pack))
    }

    private inner class PackSelectRenderSystem : RenderProducer {
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
            g.drawString("어떤 이야기를 플레이할까요?", rowX, 92f)

            val list = packs()
            if (list.isEmpty()) {
                g.font = descFont
                g.renderColor = COLOR_PACK_DESC
                g.drawString("스토리 팩을 찾을 수 없습니다. 메인 메뉴의 'Story Editor'로 새 스토리를 만들어 보세요.", rowX, listStartY)
                return
            }

            list.forEachIndexed { i, pack ->
                val ry = listStartY + i * (rowH + 16f)
                val selected = i == selectedIndex
                val hovered = i == hoverIndex

                if (selected) {
                    g.renderColor = COLOR_ROW_BG_SEL
                    g.fillRoundRect(rowX, ry, rowW, rowH, 10f)
                } else if (hovered) {
                    g.renderColor = COLOR_ROW_BG_HOVER
                    g.fillRoundRect(rowX, ry, rowW, rowH, 10f)
                }
                g.renderColor = if (selected) COLOR_ROW_BORDER_SEL else COLOR_ROW_BORDER
                g.drawRoundRect(rowX, ry, rowW, rowH, 10f)

                g.font = packTitleFont
                g.renderColor = COLOR_PACK_TITLE
                g.drawString(pack.pack.title.ifBlank { pack.pack.id }, rowX + 24f, ry + 32f)

                g.font = descFont
                g.renderColor = COLOR_PACK_DESC
                g.drawString(truncate(g, pack.pack.description, rowW - 220f), rowX + 24f, ry + 58f)

                g.font = countFont
                g.renderColor = COLOR_PACK_COUNT
                g.drawStringRight("${pack.chapters.size}개 챕터", rowX + rowW - 24f, ry + 46f)
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
