package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.render.RenderColor
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.*
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * 메인 메뉴 화면 (ECS Scene).
 *
 * 배경 비디오 프리뷰 + 별 파티클 + 메뉴 내비게이션. 도메인 엔터티가 없는 UI 허브이므로
 * World는 비워 두고 Scene 백본의 update/render 경로만 사용합니다.
 */
class MainMenuScene(private val ctx: GameContext) : Scene() {

    companion object {
        private val COLOR_BG_OVERLAY = RenderColor.of(0, 0, 0, 185)
        private val COLOR_PANEL_LEFT = RenderColor.of(18, 10, 38, 215)
        private val COLOR_PANEL_RIGHT = RenderColor.of(0, 0, 0, 0)
        private val COLOR_BTM_LEFT = RenderColor.of(0, 0, 0, 0)
        private val COLOR_BTM_RIGHT = RenderColor.of(10, 5, 28, 200)
        private val COLOR_TITLE = RenderColor.of(245, 235, 255)
        private val COLOR_SUBTITLE = RenderColor.of(160, 130, 210, 200)
        private val COLOR_LINE = RenderColor.of(100, 70, 160, 120)
        private val COLOR_ACCENT_SEL = RenderColor.of(190, 130, 255)
        private val COLOR_ACCENT_HOV = RenderColor.of(140, 100, 200, 150)
        private val COLOR_ITEM_HOV = RenderColor.of(200, 170, 240)
        private val COLOR_ITEM_NORM = RenderColor.of(160, 150, 185)
        private val COLOR_VERSION = RenderColor.of(80, 68, 110)
        private val COLOR_HINT = RenderColor.of(90, 78, 120)
        private val COLOR_CIRCLE_INNER = RenderColor.of(80, 40, 160, 35)
        private val COLOR_CIRCLE_OUTER = RenderColor.of(0, 0, 0, 0)
    }

    private val log = LoggerFactory.getLogger(MainMenuScene::class.java)

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
        super.enter()
        log.info("MainMenuScene enter, songs={}", ctx.songManager.songs.size)
        ctx.inputManager.clearEvents()
        selectedIndex = 0; hoverIndex = -1; time = 0.0
        playRandomClip()
    }

    override fun exit() {
        log.info("MainMenuScene exit")
        ctx.videoBackground.onPlayingStarted = null
        ctx.videoBackground.stop()
        super.exit()
    }

    override fun onUpdate(deltaTime: Double) {
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
        g.renderColor = COLOR_BG_OVERLAY
        g.fillRect(0, 0, w, h)

        // ── 2. 별 파티클 ──────────────────────────────────────────────────────
        for (s in stars) {
            val tw = (sin(t * 1.2f + s.twinkleOff) * 0.4f + 0.6f).toFloat()
            val a  = (s.alpha * tw * 255).toInt().coerceIn(0, 255)
            g.renderColor = RenderColor.of(200, 200, 255, a)
            g.fillCircle(s.x, s.y, s.r * tw)
        }

        // ── 3. 왼쪽 그라디언트 사이드패널 ────────────────────────────────────
        val panelW = 480
        g.fillLinearGradient(
            0f, 0f, panelW.toFloat(), h.toFloat(),
            0f, 0f, panelW.toFloat(), 0f,
            COLOR_PANEL_LEFT, COLOR_PANEL_RIGHT
        )

        // ── 4. 하단 그라디언트 ────────────────────────────────────────────────
        g.fillLinearGradient(
            0f, h * 0.72f, w.toFloat(), h * 0.28f,
            0f, h * 0.72f, 0f, h.toFloat(),
            COLOR_BTM_LEFT, COLOR_BTM_RIGHT
        )

        // ── 5. 타이틀 로고 ────────────────────────────────────────────────────
        val logoX = 60f
        val logoY = 165f

        // 글로우 레이어
        val glowAlpha = (sin(t * 0.7f) * 0.18f + 0.72f).toFloat()
        g.font  = titleFont
        g.renderColor = RenderColor.of(160, 100, 255, (glowAlpha * 90).toInt())
        g.setFontBlur(12f)
        g.drawString("StelLane", logoX - 2f, logoY + 2f)
        g.setFontBlur(0f)

        // 메인 타이틀
        g.renderColor = COLOR_TITLE
        g.drawString("StelLane", logoX, logoY)

        // 서브타이틀
        g.font  = subFont
        g.renderColor = COLOR_SUBTITLE
        g.drawString("RHYTHM GAME", logoX + 3f, logoY + 22f)

        // 가로 구분선
        val lineY = logoY + 38f
        g.renderColor = COLOR_LINE
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
                g.renderColor = RenderColor.of(120, 70, 220, barAlpha)
                g.fillRoundRect(menuStartX - 12f, fy - 28f, 300f, 42f, 8f)

                val accentH = if (selected) 32f else 20f
                val accentY = fy - accentH / 2 - 6
                g.renderColor = if (selected) COLOR_ACCENT_SEL else COLOR_ACCENT_HOV
                g.fillRoundRect(menuStartX - 16f, accentY, 3f, accentH, 2f)
            }

            val pulse = if (selected) (sin(t * 3f) * 8 + 247).toInt().coerceIn(220, 255) else 0
            g.font = if (selected || hovered) selFont else menuFont

            if (selected) {
                g.renderColor = RenderColor.of(200, 150, 255, 80)
                g.setFontBlur(4f)
                g.drawString(item, menuStartX, fy)
                g.setFontBlur(0f)
            }

            g.renderColor = when {
                selected -> RenderColor.of(255, pulse, 255)
                hovered  -> COLOR_ITEM_HOV
                else     -> COLOR_ITEM_NORM
            }
            g.drawString(item, menuStartX, fy)
        }

        // ── 7. 버전 / 힌트 ────────────────────────────────────────────────────
        g.font  = verFont
        g.renderColor = COLOR_VERSION
        g.drawString("v ${io.github.jwyoon1220.app.Const.VERSION}", logoX, h - 36f)

        g.font  = hintFont
        g.renderColor = COLOR_HINT
        g.drawString("↑↓  탐색    Enter  선택    클릭으로도 선택 가능", logoX, h.toFloat() - 18f)

        // ── 8. 우하단 장식 원 ─────────────────────────────────────────────────
        val circleX = w - 120f
        val circleY = h - 110f
        val circleR = 200f + sin(t * 0.5f).toFloat() * 10f
        g.fillRadialGradient(
            circleX - circleR, circleY - circleR, circleR * 2, circleR * 2,
            circleX, circleY, 0f, circleR,
            COLOR_CIRCLE_INNER, COLOR_CIRCLE_OUTER
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
            0 -> ctx.sceneRouter.navigate(SongSelectScene(ctx, SelectMode.PLAY))
            1 -> ctx.sceneRouter.navigate(SongSelectScene(ctx, SelectMode.EDIT))
            2 -> ctx.sceneRouter.navigate(SettingsScene(ctx, MainMenuScene(ctx)))
            3 -> ctx.sceneRouter.navigate(CreditsScene(ctx))
            4 -> ctx.sceneRouter.navigate(LicenseScene(ctx))
            5 -> exitProcess(0)
        }
    }
}
