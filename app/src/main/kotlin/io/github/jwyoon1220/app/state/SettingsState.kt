package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.AppSettings
import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.WindowMode
import io.github.jwyoon1220.core.GameState
import java.awt.Color
import java.awt.Graphics2D
import java.awt.event.KeyEvent

/**
 * 인게임 설정 화면.
 *
 * 항목:
 *  0 — 창 모드 (← → 순환)
 *  1 — 오디오 보정 오프셋 ms (← → ±10ms, Shift+←→ ±1ms)
 *
 * Esc / Enter(보정 행) → 저장 후 이전 화면으로 돌아갑니다.
 */
class SettingsState(
    private val ctx: GameContext,
    private val previous: GameState,
    private val startAt: Int = 0
) : GameState {

    private val titleFont  = FontLoader.bold(52f)
    private val labelFont  = FontLoader.semiBold(26f)
    private val valueFont  = FontLoader.regular(26f)
    private val hintFont   = FontLoader.light(14f)
    private val descFont   = FontLoader.light(13f)

    private val items = listOf("창 모드", "오디오/비디오 보정")
    private var cursor = 0

    // 로컬 편집용 (적용 전까지 AppSettings에 반영 안 함)
    private var localMode   = AppSettings.windowMode
    private var localOffset = AppSettings.calibrationOffsetMs

    private val modes = WindowMode.entries
    private val modeLabels = mapOf(
        WindowMode.WINDOWED   to "창 모드  (리사이즈 가능)",
        WindowMode.BORDERLESS to "윈도우 전체화면",
        WindowMode.EXCLUSIVE  to "독점 전체화면"
    )

    override fun enter() {
        localMode   = AppSettings.windowMode
        localOffset = AppSettings.calibrationOffsetMs
        cursor = startAt.coerceIn(0, items.lastIndex)
        ctx.inputManager.clearEvents()
    }

    override fun exit() {}

    override fun update(deltaTime: Double) {}

    override fun render(g: Graphics2D) {
        val w = g.clipBounds?.width  ?: 1280
        val h = g.clipBounds?.height ?: 720

        // 반투명 배경
        g.color = Color(0, 0, 0, 210)
        g.fillRect(0, 0, w, h)

        // 패널
        val pw = 700; val ph = 340
        val px = (w - pw) / 2; val py = (h - ph) / 2
        g.color = Color(16, 14, 30, 240)
        g.fillRoundRect(px, py, pw, ph, 18, 18)
        g.color = Color(90, 60, 160)
        g.drawRoundRect(px, py, pw, ph, 18, 18)

        // 타이틀
        g.font  = titleFont
        g.color = Color(180, 140, 255)
        val titleStr = "설정"
        val tfm = g.getFontMetrics(titleFont)
        g.drawString(titleStr, (w - tfm.stringWidth(titleStr)) / 2, py + 58)

        // 항목
        val startY = py + 110
        val rowH   = 80
        items.forEachIndexed { i, label ->
            val selected = (i == cursor)
            val rowY = startY + i * rowH

            // 선택 하이라이트
            if (selected) {
                g.color = Color(80, 50, 140, 80)
                g.fillRoundRect(px + 16, rowY - 28, pw - 32, 48, 8, 8)
                g.color = Color(140, 100, 240, 100)
                g.drawRoundRect(px + 16, rowY - 28, pw - 32, 48, 8, 8)
            }

            // 레이블
            g.font  = if (selected) labelFont else labelFont.deriveFont(labelFont.size2D)
            g.color = if (selected) Color(255, 220, 80) else Color(160, 150, 190)
            g.drawString(label, px + 36, rowY)

            // 값
            val valueStr = when (i) {
                0 -> "◀  ${modeLabels[localMode]}  ▶"
                1 -> "◀  ${if (localOffset >= 0) "+$localOffset" else "$localOffset"} ms  ▶"
                else -> ""
            }
            g.font  = valueFont
            g.color = if (selected) Color(200, 255, 200) else Color(120, 130, 150)
            val vfm = g.getFontMetrics(valueFont)
            g.drawString(valueStr, (w - vfm.stringWidth(valueStr)) / 2, rowY)
        }

        // 하단 힌트
        g.font  = hintFont
        g.color = Color(100, 100, 130)
        val hint = "↑↓ 선택   ←→ 변경   Shift+←→ 세밀 보정(±1ms)   Enter / Esc 저장 후 돌아가기"
        val hfm = g.getFontMetrics(hintFont)
        g.drawString(hint, (w - hfm.stringWidth(hint)) / 2, py + ph - 18)

        // 보정 부가 설명
        if (cursor == 1) {
            g.font  = descFont
            g.color = Color(140, 140, 160)
            val desc = "양수: 오디오가 늦게 들릴 때 (+)   음수: 오디오가 빠르게 들릴 때 (−)"
            val dfm = g.getFontMetrics(descFont)
            g.drawString(desc, (w - dfm.stringWidth(desc)) / 2, py + ph - 38)
        }
    }

    override fun keyPressed(e: KeyEvent) {
        val shift = e.isShiftDown
        when (e.keyCode) {
            KeyEvent.VK_UP   -> cursor = (cursor - 1 + items.size) % items.size
            KeyEvent.VK_DOWN -> cursor = (cursor + 1) % items.size

            KeyEvent.VK_LEFT -> when (cursor) {
                0 -> {
                    val idx = (modes.indexOf(localMode) - 1 + modes.size) % modes.size
                    localMode = modes[idx]
                }
                1 -> localOffset = (localOffset - if (shift) 1L else 10L).coerceIn(-500L, 500L)
            }
            KeyEvent.VK_RIGHT -> when (cursor) {
                0 -> {
                    val idx = (modes.indexOf(localMode) + 1) % modes.size
                    localMode = modes[idx]
                }
                1 -> localOffset = (localOffset + if (shift) 1L else 10L).coerceIn(-500L, 500L)
            }

            KeyEvent.VK_ENTER,
            KeyEvent.VK_ESCAPE -> applyAndBack()
        }
    }

    private fun applyAndBack() {
        val modeChanged = (localMode != AppSettings.windowMode)
        AppSettings.calibrationOffsetMs = localOffset
        ctx.stateManager.changeState(previous)
        // 창 모드는 상태 전환 후 적용 (전환 후 frame 재구성)
        if (modeChanged) ctx.windowManager.applyMode(localMode)
    }
}
