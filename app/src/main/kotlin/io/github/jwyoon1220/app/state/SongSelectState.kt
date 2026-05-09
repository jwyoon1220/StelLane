package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.ui.SongImportDialog
import io.github.jwyoon1220.core.GameState
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.song.ChartParser
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.SwingUtilities

enum class SelectMode { PLAY, EDIT }

class SongSelectState(
    private val ctx: GameContext,
    private val mode: SelectMode
) : GameState {

    private var songIndex = 0
    private var diffIndex = 0

    private val headerFont = Font("Arial", Font.BOLD, 36)
    private val listFont   = Font("Arial", Font.PLAIN, 28)
    private val infoFont   = Font("Arial", Font.PLAIN, 22)
    private val hintFont   = Font("Arial", Font.PLAIN, 14)

    private val songs    get() = ctx.songManager.songs
    private val curSong  get() = songs.getOrNull(songIndex)
    private val curDiffs get() = curSong?.song?.difficulties?.keys?.toList() ?: emptyList()

    override fun enter() {
        ctx.songManager.refresh()
        songIndex = 0
        diffIndex = 0
        ctx.inputManager.clearEvents()
    }

    override fun exit() {}

    override fun update(deltaTime: Double) {}

    override fun render(g: Graphics2D) {
        val w = g.clipBounds?.width  ?: 1280
        val h = g.clipBounds?.height ?: 720

        g.color = Color(0, 0, 0, 185)
        g.fillRect(0, 0, w, h)

        // 헤더
        val modeLabel = if (mode == SelectMode.PLAY) "▶  Play" else "✏  Edit Song"
        g.font  = headerFont
        g.color = Color(200, 160, 255)
        g.drawString(modeLabel, 40, 48)

        // 조작 안내
        g.font  = hintFont
        g.color = Color(120, 120, 140)
        g.drawString("↑↓: Song   ←→: Difficulty   Enter: Confirm   I: Import   Esc: Back", 40, 72)

        if (songs.isEmpty()) {
            g.font  = listFont
            g.color = Color(160, 160, 160)
            val msg = "No songs found — press I to import a song."
            val fm  = g.getFontMetrics(listFont)
            g.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2)
            return
        }

        // 왼쪽 패널: 곡 목록
        val listX = 40
        val listY = 100
        val visibleCount = minOf(songs.size, (h - listY - 20) / 38)
        val startIdx = maxOf(0, minOf(songIndex - visibleCount / 2, songs.size - visibleCount))

        for (i in startIdx until minOf(startIdx + visibleCount, songs.size)) {
            val entry   = songs[i]
            val isSel   = i == songIndex
            g.font      = listFont
            g.color     = if (isSel) Color(255, 220, 80) else Color(190, 190, 190)
            val text    = "${entry.song.artist} - ${entry.song.title}".take(38)
            g.drawString(text, listX, listY + (i - startIdx) * 38)
        }

        // 오른쪽 패널: 곡 상세 + 난이도
        curSong?.let { entry ->
            val infoX = w / 2 + 20
            var iy    = 110

            g.font  = headerFont
            g.color = Color.WHITE
            g.drawString(entry.song.title, infoX, iy); iy += 46

            g.font  = infoFont
            g.color = Color(160, 160, 160)
            g.drawString("Artist: ${entry.song.artist}", infoX, iy); iy += 30
            entry.song.bpm?.let { g.drawString("BPM: $it", infoX, iy); iy += 30 }

            iy += 16
            g.font  = listFont
            g.color = Color.WHITE
            g.drawString("Difficulty", infoX, iy); iy += 36

            curDiffs.forEachIndexed { i, diff ->
                val isSel = i == diffIndex
                g.font    = listFont
                g.color   = if (isSel) Color(255, 220, 80) else Color(190, 190, 190)
                g.drawString(if (isSel) "▶ $diff" else "  $diff", infoX + 16, iy)
                iy += 36
            }
        }
    }

    override fun keyPressed(e: KeyEvent) {
        when (e.keyCode) {
            KeyEvent.VK_UP    -> if (songs.isNotEmpty()) { songIndex = (songIndex - 1 + songs.size) % songs.size; diffIndex = 0 }
            KeyEvent.VK_DOWN  -> if (songs.isNotEmpty()) { songIndex = (songIndex + 1) % songs.size; diffIndex = 0 }
            KeyEvent.VK_LEFT  -> if (curDiffs.isNotEmpty()) diffIndex = (diffIndex - 1 + curDiffs.size) % curDiffs.size
            KeyEvent.VK_RIGHT -> if (curDiffs.isNotEmpty()) diffIndex = (diffIndex + 1) % curDiffs.size
            KeyEvent.VK_ENTER -> onConfirm()
            KeyEvent.VK_I     -> SwingUtilities.invokeLater { SongImportDialog(ctx.songManager).show() }
            KeyEvent.VK_ESCAPE -> ctx.stateManager.changeState(MainMenuState(ctx))
        }
    }

    private fun onConfirm() {
        val entry      = curSong ?: return
        val diffName   = curDiffs.getOrNull(diffIndex) ?: return
        val chartFile  = File(entry.songDir, entry.song.difficulties[diffName] ?: return)
        if (!chartFile.exists()) return
        val chart = runCatching { ChartParser.parseChart(chartFile) }.getOrNull() ?: return

        when (mode) {
            SelectMode.PLAY -> ctx.stateManager.changeState(PlayState(ctx, entry, chart))
            SelectMode.EDIT -> ctx.stateManager.changeState(EditorState(ctx, entry, chartFile, chart))
        }
    }
}
