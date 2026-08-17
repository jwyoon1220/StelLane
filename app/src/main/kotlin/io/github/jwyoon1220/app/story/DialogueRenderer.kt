package io.github.jwyoon1220.app.story

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.core.story.DialogueLine
import io.github.jwyoon1220.core.story.StoryScene
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.render.RenderColor
import java.awt.BasicStroke
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 이미지 기반 시각소설 형식으로 [StoryScene]의 현재 대사를 렌더링합니다.
 * 배경 + 캐릭터 스프라이트 + 대화창으로 구성되며, GUI 컨트롤(버튼 등)은 사용하지 않습니다.
 *
 * 이미지는 `<workingDir>/story/images/` 아래에서 [imagesDir] 기준 파일로 로드합니다
 * (스토리 챕터 JSON과 마찬가지로 유저가 직접 교체/추가할 수 있는 위치).
 */
class DialogueRenderer(private val imagesDir: File) {

    private val imageCache = HashMap<String, BufferedImage?>()

    private val nameFont = FontLoader.semiBold(22f)
    private val bodyFont = FontLoader.regular(19f)
    private val hintFont = FontLoader.light(13f)

    private fun loadImage(fileName: String): BufferedImage? =
        imageCache.getOrPut(fileName) {
            runCatching {
                val file = File(imagesDir, fileName)
                if (file.isFile) ImageIO.read(file) else null
            }.getOrNull()
        }

    /** [scene]의 [dialogueIndex]번째 대사를 그립니다. dialogues가 비어 있으면 아무것도 그리지 않습니다. */
    fun render(g: DrawContext, scene: StoryScene, dialogueIndex: Int) {
        val w = g.clipBounds.width.toFloat()
        val h = g.clipBounds.height.toFloat()

        renderBackground(g, scene, w, h)

        val lines = scene.dialogues
        if (lines.isEmpty()) return
        val idx = dialogueIndex.coerceIn(0, lines.size - 1)

        renderCharacters(g, lines, idx, w, h)
        renderDialogueBox(g, lines[idx], w, h)
        renderContinuePrompt(g, w, h, isLast = idx == lines.lastIndex)
    }

    private fun renderBackground(g: DrawContext, scene: StoryScene, w: Float, h: Float) {
        if (scene.backgroundVideo != null) {
            // 영상 자체는 Renderer가 화면 전체에 자동으로 그려주므로(Scene.rendersBackground 기본값),
            // 여기서는 대사 가독성을 위한 어둡게 처리만 얹습니다.
            g.renderColor = RenderColor.of(0, 0, 0, 90)
            g.fillRect(0f, 0f, w, h)
            return
        }
        val img = scene.backgroundImage?.let { loadImage(it) }
        if (img != null) {
            g.drawImage(img, 0f, 0f, w, h)
            g.renderColor = RenderColor.of(0, 0, 0, 90)
            g.fillRect(0f, 0f, w, h)
        } else {
            g.renderColor = RenderColor.of(6, 4, 14, 255)
            g.fillRect(0f, 0f, w, h)
        }
    }

    private fun renderCharacters(g: DrawContext, lines: List<DialogueLine>, idx: Int, w: Float, h: Float) {
        // 직전 화자가 있고 캐릭터가 바뀌었다면, 은은하게 남겨서 대화 맥락을 유지합니다.
        if (idx > 0) {
            val prev = lines[idx - 1]
            if (prev.characterImage != null && prev.character != lines[idx].character) {
                drawCharacterSprite(g, prev, w, h, alpha = 0.28f)
            }
        }
        val cur = lines[idx]
        if (cur.characterImage != null) {
            drawCharacterSprite(g, cur, w, h, alpha = 1f)
        }
    }

    private fun drawCharacterSprite(g: DrawContext, line: DialogueLine, w: Float, h: Float, alpha: Float) {
        val img = loadImage(line.characterImage ?: return) ?: return
        val boxH = h * 0.72f
        val aspect = img.width.toFloat() / img.height.toFloat()
        val boxW = boxH * aspect
        val cx = when (line.position) {
            "left" -> w * 0.22f
            "right" -> w * 0.78f
            else -> w * 0.5f
        }
        val x = cx - boxW / 2f
        val y = h - boxH - h * 0.16f
        val prevAlpha = g.globalAlpha
        g.globalAlpha = alpha
        g.drawImage(img, x, y, boxW, boxH)
        g.globalAlpha = prevAlpha
    }

    private fun renderDialogueBox(g: DrawContext, line: DialogueLine, w: Float, h: Float) {
        val boxX = w * 0.06f
        val boxY = h * 0.74f
        val boxW = w * 0.88f
        val boxH = h * 0.20f

        g.renderColor = RenderColor.of(10, 8, 20, 205)
        g.fillRoundRect(boxX, boxY, boxW, boxH, 14f)
        g.renderColor = RenderColor.of(255, 107, 157, 130)
        g.stroke = BasicStroke(1.5f)
        g.drawRoundRect(boxX, boxY, boxW, boxH, 14f)

        val isNarrator = line.character.equals("narrator", ignoreCase = true)
        if (!isNarrator) {
            g.font = nameFont
            g.renderColor = RenderColor.of(255, 200, 120)
            g.drawString(line.character, boxX + 28f, boxY + 34f)
        }

        g.font = bodyFont
        g.renderColor = if (isNarrator) RenderColor.of(220, 218, 235) else RenderColor.of(255, 255, 255)
        val textStartY = boxY + (if (isNarrator) 40f else 66f)
        drawWrappedText(g, line.text, boxX + 28f, textStartY, boxW - 56f, 26f)
    }

    private fun drawWrappedText(g: DrawContext, text: String, x: Float, y: Float, maxWidth: Float, lineHeight: Float) {
        var curY = y
        for (paragraph in text.split("\n")) {
            var line = StringBuilder()
            for (word in paragraph.split(" ")) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && g.measureStringWidth(candidate) > maxWidth) {
                    g.drawString(line.toString(), x, curY)
                    curY += lineHeight
                    line = StringBuilder(word)
                } else {
                    line = StringBuilder(candidate)
                }
            }
            g.drawString(line.toString(), x, curY)
            curY += lineHeight
        }
    }

    private fun renderContinuePrompt(g: DrawContext, w: Float, h: Float, isLast: Boolean) {
        g.font = hintFont
        g.renderColor = RenderColor.of(170, 165, 190, 210)
        val hint = if (isLast) "[클릭 / Enter로 계속]" else "[클릭 / Enter로 다음 대사]"
        g.drawStringRight(hint, w * 0.94f, h * 0.97f)
    }
}
