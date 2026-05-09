package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.ui.CalibrationDialog
import io.github.jwyoon1220.app.ui.SettingsDialog
import io.github.jwyoon1220.core.GameState
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Graphics2D
import java.awt.event.KeyEvent
import java.io.File
import kotlin.random.Random
import kotlin.system.exitProcess

class MainMenuState(private val ctx: GameContext) : GameState {

    private val log = LoggerFactory.getLogger(MainMenuState::class.java)

    private val menuItems = arrayOf("Play", "Edit Song", "Settings", "Credits", "License", "Quit")
    private var selectedIndex = 0

    private val titleFont = FontLoader.bold(72f)
    private val subFont   = FontLoader.extraLight(16f)
    private val menuFont  = FontLoader.regular(34f)
    private val selFont   = FontLoader.semiBold(40f)
    private val hintFont  = FontLoader.light(14f)

    // ── 배경 영상 미리보기 ────────────────────────────────────────────────────
    private var clipEndMs       = Long.MAX_VALUE
    private var hasVideo        = false
    private var lastPlayedTitle: String? = null

    override fun enter() {
        log.info("MainMenuState enter, songs={}", ctx.songManager.songs.size)
        ctx.inputManager.clearEvents()
        selectedIndex = 0
        playRandomClip()
    }

    override fun exit() {
        log.info("MainMenuState exit")
        ctx.videoBackground.onPlayingStarted = null
        ctx.videoBackground.stop()
    }

    override fun update(deltaTime: Double) {
        // 클립 종료 시간이 지나면 새 클립 재생
        if (hasVideo && ctx.videoBackground.getSmoothTimeMs() >= clipEndMs) {
            playRandomClip()
        }
    }

    private fun playRandomClip() {
        val candidates = ctx.songManager.songs.filter { entry ->
            entry.song.videoPath != null &&
                File(entry.songDir, entry.song.videoPath!!).exists()
        }
        if (candidates.isEmpty()) {
            log.info("재생 가능한 영상 없음 (후보 0개)")
            hasVideo = false; return
        }

        // 곡이 2개 이상이면 직전 재생곡 제외
        val pool = if (candidates.size > 1) candidates.filter { it.song.title != lastPlayedTitle } else candidates
        val entry        = pool.random()
        lastPlayedTitle  = entry.song.title
        val videoFile    = File(entry.songDir, entry.song.videoPath!!)
        val clipDuration = Random.nextLong(10_000L, 21_000L)
        val clipStart    = Random.nextLong(10_000L, 60_000L)

        clipEndMs = clipStart + clipDuration
        hasVideo  = true

        log.info("클립 재생: {} start={}ms dur={}ms", entry.song.title, clipStart, clipDuration)

        ctx.videoBackground.onPlayingStarted = {
            log.debug("onPlayingStarted → seek {}ms", clipStart)
            ctx.videoBackground.seek(clipStart)
        }
        ctx.videoBackground.play(videoFile.absolutePath)
    }

    override fun render(g: Graphics2D) {
        val w = g.clipBounds?.width  ?: 1280
        val h = g.clipBounds?.height ?: 720

        // 영상 위 반투명 어두운 오버레이
        g.color = Color(0, 0, 0, 160)
        g.fillRect(0, 0, w, h)

        // 타이틀
        g.font = titleFont
        g.color = Color(200, 160, 255)
        val title = "StelLane"
        val tfm = g.getFontMetrics(titleFont)
        g.drawString(title, (w - tfm.stringWidth(title)) / 2, h / 4)

        // 서브타이틀
        g.font = subFont
        g.color = Color(120, 100, 160)
        val sub = "Rhythm Game"
        val sfm = g.getFontMetrics(subFont)
        g.drawString(sub, (w - sfm.stringWidth(sub)) / 2, h / 4 + 28)

        // 메뉴 항목
        val startY = h / 2 - 20
        menuItems.forEachIndexed { i, item ->
            val selected = i == selectedIndex
            g.font  = if (selected) selFont else menuFont
            val fm  = g.getFontMetrics(g.font)
            g.color = if (selected) Color(255, 220, 80) else Color(200, 200, 200)
            if (selected) {
                val ix = (w - fm.stringWidth(item)) / 2
                g.color = Color(80, 60, 120, 60)
                g.fillRoundRect(ix - 16, startY + i * 58 - fm.ascent, fm.stringWidth(item) + 32, fm.height, 8, 8)
                g.color = Color(255, 220, 80)
            }
            g.drawString(item, (w - fm.stringWidth(item)) / 2, startY + i * 58)
        }

        // 조작 안내
        g.font  = hintFont
        g.color = Color(100, 100, 120)
        val hint = "↑↓  탐색     Enter  선택"
        val hfm = g.getFontMetrics(hintFont)
        g.drawString(hint, (w - hfm.stringWidth(hint)) / 2, h - 30)
    }

    override fun keyPressed(e: KeyEvent) {
        when (e.keyCode) {
            KeyEvent.VK_UP    -> selectedIndex = (selectedIndex - 1 + menuItems.size) % menuItems.size
            KeyEvent.VK_DOWN  -> selectedIndex = (selectedIndex + 1) % menuItems.size
            KeyEvent.VK_ENTER -> onSelect()
        }
    }

    private fun onSelect() {
        log.info("선택: {} (index={})", menuItems[selectedIndex], selectedIndex)
        when (selectedIndex) {
            0 -> ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.PLAY))
            1 -> ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.EDIT))
            2 -> {
                SettingsDialog(ctx.windowManager.frame, ctx.windowManager).isVisible = true
            }
            3 -> ctx.stateManager.changeState(CreditsState(ctx))
            4 -> ctx.stateManager.changeState(LicenseState(ctx))
            5 -> exitProcess(0)
        }
    }
}

