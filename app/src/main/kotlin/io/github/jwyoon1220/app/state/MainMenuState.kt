package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.GameState
import io.github.jwyoon1220.engine.Keys
import org.slf4j.LoggerFactory
import java.awt.Color
import java.io.File
import kotlin.math.*
import kotlin.random.Random
import kotlin.system.exitProcess

class MainMenuState(private val ctx: GameContext) : GameState {

    private val log = LoggerFactory.getLogger(MainMenuState::class.java)

    private val menuItems = arrayOf("Play", "Edit Song", "Settings", "Credits", "License", "Quit")
    private var selectedIndex = 0
    private var hoverIndex    = -1

    // 폰트
    private val titleFont = FontLoader.bold(82f)
    private val subFont   = FontLoader.extraLight(15f)
    private val menuFont  = FontLoader.regular(30f)
    private val selFont   = FontLoader.semiBold(34f)
    private val hintFont  = FontLoader.light(13f)
    private val verFont   = FontLoader.light(12f)

    // 애니메이션 시간
    private var time = 0.0

    // 별 파티클
    private data class Star(var x: Float, var y: Float, val r: Float, val speed: Float, val alpha: Float, val twinkleOff: Float)
    private val stars: List<Star> = List(120) {
        Star(
            x          = Random.nextFloat() * 1280f,
            y          = Random.nextFloat() * 720f,
            r          = Random.nextFloat() * 1.5f + 0.3f,
            speed      = Random.nextFloat() * 0.3f + 0.05f,
            alpha      = Random.nextFloat() * 0.6f + 0.2f,
            twinkleOff = Random.nextFloat() * Math.PI.toFloat() * 2f
        )
    }

    // 비디오 프리뷰
    private var clipEndMs       = Long.MAX_VALUE
    private var hasVideo        = false
    private var lastPlayedTitle: String? = null

    private var mouseX = 0f
    private var mouseY = 0f

    override fun enter() {
        log.info("MainMenuState enter, songs={}", ctx.songManager.songs.size)
        ctx.inputManager.clearEvents()
        selectedIndex = 0; hoverIndex = -1; time = 0.0
        playRandomClip()
    }

    override fun exit() {
        log.info("MainMenuState exit")
        ctx.videoBackground.onPlayingStarted = null
        ctx.videoBackground.stop()
    }

    override fun update(deltaTime: Double) {
        time += deltaTime
        for (s in stars) {
            s.y -= s.speed
            if (s.y < -2f) s.y = 722f
        }
        if (hasVideo && ctx.videoBackground.getSmoothTimeMs() >= clipEndMs) playRandomClip()
    }

    private fun playRandomClip() {
        val candidates = ctx.songManager.songs.filter { e ->
            e.song.videoPath != null && File(e.songDir, e.song.videoPath!!).exists()
        }
        if (candidates.isEmpty()) { hasVideo = false; return }
        val pool  = if (candidates.size > 1) candidates.filter { it.song.title != lastPlayedTitle } else candidates
        val entry = pool.random()
        lastPlayedTitle = entry.song.title
        val videoFile    = File(entry.songDir, entry.song.videoPath!!)
        val clipDuration = Random.nextLong(10_000L, 21_000L)
        val clipStart    = Random.nextLong(10_000L, 60_000L)
        clipEndMs = clipStart + clipDuration; hasVideo = true
        ctx.videoBackground.onPlayingStarted = { ctx.videoBackground.seek(clipStart) }
        ctx.videoBackground.play(videoFile.absolutePath)
    }

    override fun render(g: DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height
        val t = time.toFloat()

        // ── 1. 비디오 위 어두운 오버레이 ─────────────────────────────────────
        g.color = Color(0, 0, 0, 185)
        g.fillRect(0, 0, w, h)

        // ── 2. 별 파티클 ──────────────────────────────────────────────────────
        for (s in stars) {
            val tw = (sin(t * 1.2f + s.twinkleOff) * 0.4f + 0.6f).toFloat()
            val a  = (s.alpha * tw * 255).toInt().coerceIn(0, 255)
            g.color = Color(200, 200, 255, a)
            g.fillCircle(s.x, s.y, s.r * tw)
        }

        // ── 3. 왼쪽 그라디언트 사이드패널 ────────────────────────────────────
        val panelW = 480
        g.fillLinearGradient(
            0f, 0f, panelW.toFloat(), h.toFloat(),
            0f, 0f, panelW.toFloat(), 0f,
            Color(18, 10, 38, 215), Color(0, 0, 0, 0)
        )

        // ── 4. 하단 그라디언트 ────────────────────────────────────────────────
        g.fillLinearGradient(
            0f, h * 0.72f, w.toFloat(), h * 0.28f,
            0f, h * 0.72f, 0f, h.toFloat(),
            Color(0, 0, 0, 0), Color(10, 5, 28, 200)
        )

        // ── 5. 타이틀 로고 ────────────────────────────────────────────────────
        val logoX = 60f
        val logoY = 165f

        // 글로우 레이어
        val glowAlpha = (sin(t * 0.7f) * 0.18f + 0.72f).toFloat()
        g.font  = titleFont
        g.color = Color(160, 100, 255, (glowAlpha * 90).toInt())
        g.setFontBlur(12f)
        g.drawString("StelLane", logoX - 2f, logoY + 2f)
        g.setFontBlur(0f)

        // 메인 타이틀
        g.color = Color(245, 235, 255)
        g.drawString("StelLane", logoX, logoY)

        // 서브타이틀
        g.font  = subFont
        g.color = Color(160, 130, 210, 200)
        g.drawString("RHYTHM GAME", logoX + 3f, logoY + 22f)

        // 가로 구분선
        val lineY = logoY + 38f
        g.color = Color(100, 70, 160, 120)
        g.stroke = java.awt.BasicStroke(1f)
        g.drawLine(logoX.toInt(), lineY.toInt(), (logoX + 200).toInt(), lineY.toInt())

        // ── 6. 메뉴 항목 ─────────────────────────────────────────────────────
        val menuStartX = logoX
        val menuStartY = logoY + 80f
        val rowH       = 58f

        menuItems.forEachIndexed { i, item ->
            val selected = (i == selectedIndex)
            val hovered  = (i == hoverIndex)
            val fy       = menuStartY + i * rowH

            if (selected || hovered) {
                val barAlpha = if (selected) (sin(t * 2.5f) * 20 + 60).toInt() else 35
                g.color = Color(120, 70, 220, barAlpha)
                g.fillRoundRect(menuStartX - 12f, fy - 28f, 300f, 42f, 8f)

                val accentH = if (selected) 32f else 20f
                val accentY = fy - accentH / 2 - 6
                g.color = if (selected) Color(190, 130, 255) else Color(140, 100, 200, 150)
                g.fillRoundRect(menuStartX - 16f, accentY, 3f, accentH, 2f)
            }

            val pulse = if (selected) (sin(t * 3f) * 8 + 247).toInt().coerceIn(220, 255) else 0
            g.font = if (selected || hovered) selFont else menuFont

            if (selected) {
                g.color = Color(200, 150, 255, 80)
                g.setFontBlur(4f)
                g.drawString(item, menuStartX, fy)
                g.setFontBlur(0f)
            }

            g.color = when {
                selected -> Color(255, pulse, 255)
                hovered  -> Color(200, 170, 240)
                else     -> Color(160, 150, 185)
            }
            g.drawString(item, menuStartX, fy)
        }

        // ── 7. 버전 / 힌트 ────────────────────────────────────────────────────
        g.font  = verFont
        g.color = Color(80, 68, 110)
        g.drawString("v B0.1.2", logoX, h - 36f)

        g.font  = hintFont
        g.color = Color(90, 78, 120)
        g.drawString("↑↓  탐색    Enter  선택    클릭으로도 선택 가능", logoX, h.toFloat() - 18f)

        // ── 8. 우하단 장식 원 ─────────────────────────────────────────────────
        val circleX = w - 120f
        val circleY = h - 110f
        val circleR = 200f + sin(t * 0.5f).toFloat() * 10f
        g.fillRadialGradient(
            circleX - circleR, circleY - circleR, circleR * 2, circleR * 2,
            circleX, circleY, 0f, circleR,
            Color(80, 40, 160, 35), Color(0, 0, 0, 0)
        )
    }

    override fun keyPressed(key: Int, mods: Int) {
        when (key) {
            Keys.UP    -> selectedIndex = (selectedIndex - 1 + menuItems.size) % menuItems.size
            Keys.DOWN  -> selectedIndex = (selectedIndex + 1) % menuItems.size
            Keys.ENTER -> onSelect()
        }
    }

    override fun mouseDragged(x: Float, y: Float, button: Int) { mouseX = x; mouseY = y; updateHover(x, y) }
    override fun mousePressed(x: Float, y: Float, button: Int, mods: Int) { mouseX = x; mouseY = y; updateHover(x, y) }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        mouseX = x; mouseY = y; updateHover(x, y)
        if (hoverIndex >= 0) { selectedIndex = hoverIndex; onSelect() }
    }

    private fun updateHover(x: Float, y: Float) {
        val logoY      = 165f
        val menuStartX = 60f
        val menuStartY = logoY + 80f
        val rowH       = 58f
        hoverIndex = -1
        menuItems.forEachIndexed { i, _ ->
            val fy  = menuStartY + i * rowH
            if (x in menuStartX..(menuStartX + 320f) && y in (fy - 30f)..(fy + 16f)) hoverIndex = i
        }
    }

    private fun onSelect() {
        log.info("선택: {} (index={})", menuItems[selectedIndex], selectedIndex)
        when (selectedIndex) {
            0 -> ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.PLAY))
            1 -> ctx.stateManager.changeState(SongSelectState(ctx, SelectMode.EDIT))
            2 -> ctx.stateManager.changeState(SettingsState(ctx, MainMenuState(ctx)))
            3 -> ctx.stateManager.changeState(CreditsState(ctx))
            4 -> ctx.stateManager.changeState(LicenseState(ctx))
            5 -> exitProcess(0)
        }
    }
}
