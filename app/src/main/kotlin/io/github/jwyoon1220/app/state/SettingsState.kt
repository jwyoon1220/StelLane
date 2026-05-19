package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.AppSettings
import io.github.jwyoon1220.app.EditorRenderBackend
import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.PlayRenderBackend
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.DrawFont
import io.github.jwyoon1220.engine.GameState
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.WindowMode
import java.awt.Color
import kotlin.math.sin

/**
 * 인게임 설정 화면 (NanoVG DrawContext 기반 프리미엄 UI).
 *
 * 항목:
 *  0 — 창 모드
 *  1 — 오디오 보정 오프셋
 *  2 — 프레임 제한
 *  3 — 플레이 렌더러
 *  4 — 에디터 렌더러
 */
class SettingsState(
    private val ctx: GameContext,
    private val previous: GameState,
    private val startAt: Int = 0
) : GameState {

    private val titleFont  = FontLoader.bold(48f)
    private val labelFont  = FontLoader.semiBold(22f)
    private val valueFont  = FontLoader.regular(20f)
    private val arrowFont  = FontLoader.regular(18f)
    private val descFont   = FontLoader.light(12f)
    private val hintFont   = FontLoader.light(12f)

    private val items = listOf("창 모드", "오디오/비디오 보정", "프레임 제한", "플레이 렌더러", "에디터 렌더러")
    private var cursor = 0

    // 로컬 편집용
    private var localMode                = AppSettings.windowMode
    private var localOffset              = AppSettings.calibrationOffsetMs
    private var localFps                 = AppSettings.targetFps
    private var localPlayRenderBackend   = AppSettings.playRenderBackend
    private var localEditorRenderBackend = AppSettings.editorRenderBackend

    private val fpsOptions           = arrayOf(30, 60, 120, 144, 165, 240, 360, 480, 720)
    private val playBackendOptions   = PlayRenderBackend.entries
    private val editorBackendOptions = EditorRenderBackend.entries
    private val playBackendLabel     = mapOf(PlayRenderBackend.NANOVG to "NanoVG", PlayRenderBackend.CUSTOM to "OpenGL (권장)")
    private val editorBackendLabel   = mapOf(EditorRenderBackend.NANOVG to "NanoVG", EditorRenderBackend.CUSTOM to "OpenGL (권장)")
    private val modes                = WindowMode.entries
    private val modeLabels           = mapOf(
        WindowMode.WINDOWED   to "창 모드 (리사이즈 가능)",
        WindowMode.BORDERLESS to "윈도우 전체화면",
        WindowMode.EXCLUSIVE  to "독점 전체화면"
    )

    private var time    = 0.0
    private var hoverIdx= -1

    override fun enter() {
        localMode                = AppSettings.windowMode
        localOffset              = AppSettings.calibrationOffsetMs
        localFps                 = AppSettings.targetFps
        localPlayRenderBackend   = AppSettings.playRenderBackend
        localEditorRenderBackend = AppSettings.editorRenderBackend
        cursor = startAt.coerceIn(0, items.lastIndex)
        time   = 0.0; hoverIdx = -1
        ctx.inputManager.clearEvents()
    }

    override fun exit() {}
    override fun update(deltaTime: Double) { time += deltaTime }

    override fun render(g: DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height
        val t = time.toFloat()

        // ── 반투명 전체 오버레이 ─────────────────────────────────────────────
        g.color = Color(0, 0, 0, 200)
        g.fillRect(0, 0, w, h)

        // ── 중앙 패널 ────────────────────────────────────────────────────────
        val pw = 720; val ph = 540
        val px = (w - pw) / 2; val py = (h - ph) / 2

        // 패널 배경 그라디언트
        g.fillLinearGradient(
            px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(),
            px.toFloat(), py.toFloat(), px.toFloat(), (py + ph).toFloat(),
            Color(18, 12, 40, 248), Color(10, 6, 26, 248)
        )

        // 패널 테두리 글로우
        val glowAlpha = (sin(t * 1.5f) * 20 + 80).toInt()
        g.color = Color(100, 60, 200, glowAlpha)
        g.drawRoundRect(px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(), 18f)
        g.color = Color(60, 30, 120, 60)
        g.drawRoundRect((px - 1).toFloat(), (py - 1).toFloat(), (pw + 2).toFloat(), (ph + 2).toFloat(), 19f)

        // ── 패널 헤더 ─────────────────────────────────────────────────────────
        g.fillLinearGradient(
            px.toFloat(), py.toFloat(), pw.toFloat(), 72f,
            px.toFloat(), py.toFloat(), px.toFloat(), (py + 72).toFloat(),
            Color(45, 20, 100, 200), Color(20, 10, 50, 100)
        )
        g.color = Color(70, 35, 140, 120)
        g.drawLine(px, py + 72, px + pw, py + 72)

        // 타이틀 글로우
        g.font  = titleFont
        g.color = Color(150, 80, 255, 60)
        g.setFontBlur(6f)
        g.drawStringCentered("설정", w / 2f, py + 54f)
        g.setFontBlur(0f)
        g.color = Color(210, 175, 255)
        g.drawStringCentered("설정", w / 2f, py + 54f)

        // ── 설정 항목 ─────────────────────────────────────────────────────────
        val itemStartY = py + 72 + 36
        val rowH       = 76

        items.forEachIndexed { i, label ->
            val selected = (i == cursor)
            val hovered  = (i == hoverIdx)
            val rowY     = itemStartY + i * rowH

            if (selected) {
                g.fillLinearGradient(
                    (px + 12).toFloat(), rowY.toFloat(), (pw - 24).toFloat(), rowH.toFloat(),
                    (px + 12).toFloat(), 0f, (px + pw - 12).toFloat(), 0f,
                    Color(70, 35, 150, 100), Color(40, 20, 80, 40)
                )
                val bAlpha = (sin(t * 2f) * 20 + 80).toInt()
                g.color = Color(120, 70, 220, bAlpha)
                g.drawRoundRect((px + 12).toFloat(), rowY.toFloat(), (pw - 24).toFloat(), (rowH - 8).toFloat(), 10f)
                // 왼쪽 선택 인디케이터
                g.color = Color(170, 100, 255)
                g.fillRoundRect((px + 12).toFloat(), (rowY + 10).toFloat(), 3f, (rowH - 28).toFloat(), 2f)
            } else if (hovered) {
                g.color = Color(255, 255, 255, 8)
                g.fillRoundRect((px + 12).toFloat(), rowY.toFloat(), (pw - 24).toFloat(), (rowH - 8).toFloat(), 10f)
            }

            // 레이블
            val midY = (rowY + rowH / 2 + 8).toFloat()
            g.font  = labelFont
            g.color = if (selected) Color(255, 230, 100) else if (hovered) Color(190, 165, 230) else Color(140, 125, 175)
            g.drawString(label, (px + 28).toFloat(), midY)

            // 현재 값
            val valueStr = when (i) {
                0 -> modeLabels[localMode] ?: ""
                1 -> if (localOffset >= 0) "+$localOffset ms" else "$localOffset ms"
                2 -> "$localFps FPS"
                3 -> playBackendLabel[localPlayRenderBackend] ?: ""
                4 -> editorBackendLabel[localEditorRenderBackend] ?: ""
                else -> ""
            }

            val arrowColor = if (selected || hovered) Color(180, 130, 255) else Color(80, 65, 110)
            val valueRight = (px + pw - 24).toFloat()

            // ▶ 버튼
            g.font  = arrowFont
            g.color = arrowColor
            g.drawStringRight("▶", valueRight, midY)

            // 값 텍스트
            g.font  = valueFont
            g.color = if (selected) Color(200, 255, 200) else if (hovered) Color(160, 220, 160) else Color(100, 115, 130)
            g.drawStringRight(valueStr, valueRight - 24f, midY)

            // ◀ 버튼
            val valueW = g.measureStringWidth(valueStr, valueFont)
            g.font  = arrowFont
            g.color = arrowColor
            g.drawStringRight("◀", valueRight - 24f - valueW - 12f, midY)

            // 구분선 (마지막 줄 제외)
            if (i < items.lastIndex) {
                g.color = Color(40, 28, 70, 100)
                g.drawLine(px + 24, rowY + rowH - 1, px + pw - 24, rowY + rowH - 1)
            }
        }

        // ── 부가 설명 ─────────────────────────────────────────────────────────
        g.font  = descFont
        g.color = Color(110, 95, 145)
        val descText = when (cursor) {
            1 -> "양수: 오디오가 늦게 들릴 때 (+)   음수: 오디오가 빠르게 들릴 때 (−)"
            2 -> "낮을수록 CPU 사용량 감소, 높을수록 화면이 부드럽습니다"
            3 -> "플레이 렌더는 Custom OpenGL(GPU) 사용을 권장합니다"
            4 -> "에디터 렌더도 Custom OpenGL(GPU) 사용을 권장합니다"
            else -> ""
        }
        if (descText.isNotEmpty()) g.drawStringCentered(descText, w / 2f, py + ph - 46f)

        // ── 하단 힌트 ─────────────────────────────────────────────────────────
        g.font  = hintFont
        g.color = Color(80, 68, 108)
        g.drawStringCentered("↑↓ 선택   ←→ 변경   Shift+←→ 세밀 보정(±1ms)   Enter / Esc 저장 후 돌아가기", w / 2f, py + ph - 22f)
    }

    // ── 입력 ─────────────────────────────────────────────────────────────────
    override fun keyPressed(key: Int, mods: Int) {
        val shift = Keys.isShift(mods)
        when (key) {
            Keys.UP    -> cursor = (cursor - 1 + items.size) % items.size
            Keys.DOWN  -> cursor = (cursor + 1) % items.size
            Keys.LEFT  -> changeValue(cursor, -1, shift)
            Keys.RIGHT -> changeValue(cursor, +1, shift)
            Keys.ENTER, Keys.ESCAPE -> applyAndBack()
        }
    }

    override fun mousePressed(x: Float, y: Float, button: Int, mods: Int) { updateHover(x, y) }
    override fun mouseDragged(x: Float, y: Float, button: Int) { updateHover(x, y) }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        updateHover(x, y)
        val pw = 720; val ph = 540
        val px = (1280 - pw) / 2; val py = (720 - ph) / 2

        if (x < px || x > px + pw || y < py || y > py + ph) { applyAndBack(); return }

        val itemStartY = py + 72 + 36; val rowH = 76
        items.forEachIndexed { i, _ ->
            val rowY = itemStartY + i * rowH
            if (y < rowY || y > rowY + rowH - 1) return@forEachIndexed
            cursor = i
            val centerX = 1280 / 2f
            if (x < centerX - 20) changeValue(i, -1, false)
            else if (x > centerX + 20) changeValue(i, +1, false)
        }
    }

    private fun changeValue(idx: Int, dir: Int, shift: Boolean) {
        when (idx) {
            0 -> { val n = (modes.indexOf(localMode) + dir + modes.size) % modes.size; localMode = modes[n] }
            1 -> localOffset += if (shift) dir.toLong() else dir.toLong() * 10L
            2 -> { val n = (fpsOptions.indexOf(localFps) + dir + fpsOptions.size) % fpsOptions.size; localFps = fpsOptions[n] }
            3 -> { val n = (playBackendOptions.indexOf(localPlayRenderBackend) + dir + playBackendOptions.size) % playBackendOptions.size; localPlayRenderBackend = playBackendOptions[n] }
            4 -> { val n = (editorBackendOptions.indexOf(localEditorRenderBackend) + dir + editorBackendOptions.size) % editorBackendOptions.size; localEditorRenderBackend = editorBackendOptions[n] }
        }
    }

    private fun updateHover(x: Float, y: Float) {
        val pw = 720; val ph = 540
        val px = (1280 - pw) / 2; val py = (720 - ph) / 2
        if (x < px || x > px + pw || y < py || y > py + ph) { hoverIdx = -1; return }
        val itemStartY = py + 72 + 36; val rowH = 76
        hoverIdx = -1
        items.forEachIndexed { i, _ ->
            val rowY = itemStartY + i * rowH
            if (y in rowY.toFloat()..(rowY + rowH - 1).toFloat()) hoverIdx = i
        }
    }

    private fun applyAndBack() {
        val modeChanged = (localMode != AppSettings.windowMode)
        AppSettings.calibrationOffsetMs     = localOffset
        AppSettings.targetFps               = localFps
        AppSettings.playRenderBackend       = localPlayRenderBackend
        AppSettings.editorRenderBackend     = localEditorRenderBackend
        if (ctx.gameLoop.targetFPS != localFps) ctx.gameLoop.targetFPS = localFps
        ctx.stateManager.changeState(previous)
        if (modeChanged) ctx.windowManager.applyMode(localMode)
    }
}
