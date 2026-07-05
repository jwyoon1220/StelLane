package io.github.jwyoon1220.app.multiplayer

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.multiplayer.MultiplayerManager
import io.github.jwyoon1220.engine.render.RenderColor

private val panelTitleFont = FontLoader.semiBold(15f)
private val panelRowFont = FontLoader.regular(13f)

/**
 * 오프셋 보정 대기 화면 왼쪽 위 오버레이. [io.github.jwyoon1220.app.multiplayer.MultiplayerPlayScene]에서
 *
 * [localDone]은 로컬 플레이어 자신의 보정(오프셋 측정 + ReadyMsg 전송) 완료 여부이며,
 * 나머지 참가자의 완료 여부는 [MultiplayerManager.remotePlayers]의 calibrated 플래그로 표시합니다.
 */
fun renderCalibrationWaitPanel(g: DrawContext, manager: MultiplayerManager, localDone: Boolean) {
    val others = manager.remotePlayers.values.filter { it.id != manager.localPlayerId }
    val rowH = 20f
    val panelW = 220f
    val panelH = 40f + rowH * others.size.coerceAtLeast(1)
    val panelX = 10f
    val panelY = 10f

    g.renderColor = RenderColor.of(0, 0, 0, 160)
    g.fillRoundRect(panelX, panelY, panelW, panelH, 8f)

    g.font = panelTitleFont
    g.renderColor = if (localDone) RenderColor.of(120, 255, 160) else RenderColor.of(255, 210, 80)
    val title = if (localDone) "오프셋 조정 완료 (대기 중…)" else "오프셋 조정 중…"
    g.drawString(title, panelX + 10f, panelY + 20f)

    g.font = panelRowFont
    if (others.isEmpty()) {
        g.renderColor = RenderColor.of(140, 130, 170)
        g.drawString("다른 참가자 없음", panelX + 10f, panelY + 42f)
    } else {
        others.forEachIndexed { i, p ->
            val rowY = panelY + 38f + i * rowH
            val statusText = if (p.calibrated) "완료" else "조정 중"
            val statusColor = if (p.calibrated) RenderColor.of(120, 255, 160) else RenderColor.of(220, 170, 90)
            g.renderColor = RenderColor.of(200, 200, 230)
            val name = if (p.name.length > 10) p.name.take(10) + "…" else p.name
            g.drawString(name, panelX + 10f, rowY + rowH - 6f)
            g.renderColor = statusColor
            g.drawStringRight(statusText, panelX + panelW - 10f, rowY + rowH - 6f)
        }
    }
}
