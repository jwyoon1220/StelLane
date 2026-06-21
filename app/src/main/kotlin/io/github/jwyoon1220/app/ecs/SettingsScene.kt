package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.AppSettings
import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.GameState
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.WindowMode
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.render.RenderColor
import kotlin.math.sin

/**
 * 인게임 설정 화면 (ECS Scene, NanoVG DrawContext 기반 UI).
 *
 * 순수 UI 화면이라 도메인 엔터티 없이 Scene 백본의 update/render 경로만 사용합니다.
 * 돌아갈 화면은 [previous] 로 주입받습니다 (메인 메뉴 / 에디터 등).
 */
class SettingsScene(
    private val ctx: GameContext,
    private val previous: GameState,
    private val startAt: Int = 0
) : Scene() {

    private val titleFont  = FontLoader.bold(48f)
    private val labelFont  = FontLoader.semiBold(22f)
    private val valueFont  = FontLoader.regular(20f)
    private val arrowFont  = FontLoader.regular(18f)
    private val descFont   = FontLoader.light(12f)
    private val hintFont   = FontLoader.light(12f)

    private val items = listOf("창 모드", "오디오/비디오 보정", "Music Volume", "프레임 제한", "VSync", "닉네임")
    private var cursor = 0

    // 로컬 편집용
    private var localMode     = AppSettings.windowMode
    private var localOffset   = AppSettings.calibrationOffsetMs
    private var localVolume   = AppSettings.musicVolume
    private var localFps      = AppSettings.targetFps
    private var localVSync    = AppSettings.vSync
    private var localNickname = AppSettings.nickname
    private var editingNickname = false
    private var volumeDragActive = false

    private val fpsOptions = arrayOf(30, 60, 120, 144, 165, 240, 360, 480, 720)
    private val modes      = WindowMode.entries
    private val modeLabels           = mapOf(
        WindowMode.WINDOWED   to "창 모드 (리사이즈 가능)",
        WindowMode.BORDERLESS to "윈도우 전체화면",
        WindowMode.EXCLUSIVE  to "독점 전체화면"
    )

    private var time    = 0.0
    private var hoverIdx= -1

    override fun enter() {
        super.enter()
        localMode     = AppSettings.windowMode
        localOffset   = AppSettings.calibrationOffsetMs
        localVolume   = AppSettings.musicVolume
        localFps      = AppSettings.targetFps
        localVSync    = AppSettings.vSync
        localNickname = AppSettings.nickname
        editingNickname = false
        cursor = startAt.coerceIn(0, items.lastIndex)
        time   = 0.0; hoverIdx = -1
        volumeDragActive = false
        ctx.inputManager.clearEvents()
    }

    override fun onUpdate(deltaTime: Double) { time += deltaTime }

    override fun render(g: DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height
        val t = time.toFloat()

        // ── 반투명 전체 오버레이 ─────────────────────────────────────────────
        g.renderColor = RenderColor.of(0, 0, 0, 200)
        g.fillRect(0, 0, w, h)

        // ── 중앙 패널 ────────────────────────────────────────────────────────
        val pw = 720; val ph = 650
        val px = (w - pw) / 2; val py = (h - ph) / 2

        // 패널 배경 그라디언트
        g.fillLinearGradient(
            px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(),
            px.toFloat(), py.toFloat(), px.toFloat(), (py + ph).toFloat(),
            RenderColor.of(18, 12, 40, 248), RenderColor.of(10, 6, 26, 248)
        )

        // 패널 테두리 글로우
        val glowAlpha = (sin(t * 1.5f) * 20 + 80).toInt()
        g.renderColor = RenderColor.of(100, 60, 200, glowAlpha)
        g.drawRoundRect(px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(), 18f)
        g.renderColor = RenderColor.of(60, 30, 120, 60)
        g.drawRoundRect((px - 1).toFloat(), (py - 1).toFloat(), (pw + 2).toFloat(), (ph + 2).toFloat(), 19f)

        // ── 패널 헤더 ─────────────────────────────────────────────────────────
        g.fillLinearGradient(
            px.toFloat(), py.toFloat(), pw.toFloat(), 72f,
            px.toFloat(), py.toFloat(), px.toFloat(), (py + 72).toFloat(),
            RenderColor.of(45, 20, 100, 200), RenderColor.of(20, 10, 50, 100)
        )
        g.renderColor = RenderColor.of(70, 35, 140, 120)
        g.drawLine(px, py + 72, px + pw, py + 72)

        // 타이틀 글로우
        g.font  = titleFont
        g.renderColor = RenderColor.of(150, 80, 255, 60)
        g.setFontBlur(6f)
        g.drawStringCentered("설정", w / 2f, py + 54f)
        g.setFontBlur(0f)
        g.renderColor = RenderColor.of(210, 175, 255)
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
                    RenderColor.of(70, 35, 150, 100), RenderColor.of(40, 20, 80, 40)
                )
                val bAlpha = (sin(t * 2f) * 20 + 80).toInt()
                g.renderColor = RenderColor.of(120, 70, 220, bAlpha)
                g.drawRoundRect((px + 12).toFloat(), rowY.toFloat(), (pw - 24).toFloat(), (rowH - 8).toFloat(), 10f)
                // 왼쪽 선택 인디케이터
                g.renderColor = RenderColor.of(170, 100, 255)
                g.fillRoundRect((px + 12).toFloat(), (rowY + 10).toFloat(), 3f, (rowH - 28).toFloat(), 2f)
            } else if (hovered) {
                g.renderColor = RenderColor.of(255, 255, 255, 8)
                g.fillRoundRect((px + 12).toFloat(), rowY.toFloat(), (pw - 24).toFloat(), (rowH - 8).toFloat(), 10f)
            }

            // 레이블
            val midY = (rowY + rowH / 2 + 8).toFloat()
            g.font  = labelFont
            g.renderColor = if (selected) RenderColor.of(255, 230, 100) else if (hovered) RenderColor.of(190, 165, 230) else RenderColor.of(140, 125, 175)
            g.drawString(label, (px + 28).toFloat(), midY)

            // 닉네임 항목 전용 렌더링
            if (i == 5) {
                val fieldW = 220f
                val fieldX = (px + pw - 24).toFloat() - fieldW
                val fieldY = midY - 20f
                val fieldH = 30f
                val isEditing = editingNickname && selected

                g.renderColor = if (isEditing) RenderColor.of(50, 30, 100, 220) else RenderColor.of(30, 20, 60, 180)
                g.fillRoundRect(fieldX, fieldY, fieldW, fieldH, 6f)
                g.renderColor = if (isEditing) RenderColor.of(160, 100, 255) else RenderColor.of(80, 60, 130)
                g.drawRoundRect(fieldX, fieldY, fieldW, fieldH, 6f)

                val displayText = if (isEditing && (t * 2).toInt() % 2 == 0)
                    "$localNickname|" else localNickname.ifEmpty { "미설정" }
                g.font  = valueFont
                g.renderColor = if (isEditing) RenderColor.WHITE else RenderColor.of(180, 160, 220)
                g.drawString(displayText, fieldX + 8f, fieldY + fieldH - 8f)

                if (!isEditing && selected) {
                    g.font  = hintFont
                    g.renderColor = RenderColor.of(120, 100, 160)
                    g.drawString("Enter: 편집", fieldX, fieldY + fieldH + 12f)
                }
                if (i < items.lastIndex) {
                    g.renderColor = RenderColor.of(40, 28, 70, 100)
                    g.drawLine(px + 24, rowY + rowH - 1, px + pw - 24, rowY + rowH - 1)
                }
                return@forEachIndexed
            }

            // 현재 값
            val valueStr = when (i) {
                0 -> modeLabels[localMode] ?: ""
                1 -> if (localOffset >= 0) "+$localOffset ms" else "$localOffset ms"
                2 -> "${(localVolume * 100).toInt()}%"
                3 -> "$localFps FPS"
                4 -> if (localVSync) "켜짐" else "꺼짐"
                else -> ""
            }

            val arrowColor = if (selected || hovered) RenderColor.of(180, 130, 255) else RenderColor.of(80, 65, 110)
            val valueRight = (px + pw - 24).toFloat()

            // ▶ 버튼
            g.font  = arrowFont
            g.renderColor = arrowColor
            g.drawStringRight("▶", valueRight, midY)

            // 값 텍스트
            g.font  = valueFont
            g.renderColor = if (selected) RenderColor.of(200, 255, 200) else if (hovered) RenderColor.of(160, 220, 160) else RenderColor.of(100, 115, 130)
            g.drawStringRight(valueStr, valueRight - 24f, midY)

            // ◀ 버튼
            val valueW = g.measureStringWidth(valueStr, valueFont)
            g.font  = arrowFont
            g.renderColor = arrowColor
            g.drawStringRight("◀", valueRight - 24f - valueW - 12f, midY)

            if (i == 2) {
                val sliderW = 150f
                val sliderX = px + pw - 300f
                val sliderY = midY - 6f
                val sliderH = 6f

                // Track
                g.renderColor = RenderColor.of(50, 40, 80)
                g.fillRoundRect(sliderX, sliderY, sliderW, sliderH, 3f)

                // Fill
                g.renderColor = if (selected) RenderColor.of(170, 100, 255) else RenderColor.of(110, 70, 180)
                g.fillRoundRect(sliderX, sliderY, sliderW * localVolume, sliderH, 3f)

                // Knob
                g.renderColor = RenderColor.WHITE
                g.fillOval((sliderX + sliderW * localVolume - 5f).toInt(), (sliderY - 2f).toInt(), 10, 10)
            }

            // 구분선 (마지막 줄 제외)
            if (i < items.lastIndex) {
                g.renderColor = RenderColor.of(40, 28, 70, 100)
                g.drawLine(px + 24, rowY + rowH - 1, px + pw - 24, rowY + rowH - 1)
            }
        }

        // ── 부가 설명 ─────────────────────────────────────────────────────────
        g.font  = descFont
        g.renderColor = RenderColor.of(110, 95, 145)
        val descText = when (cursor) {
            1 -> "양수: 오디오가 늦게 들릴 때 (+)   음수: 오디오가 빠르게 들릴 때 (−)"
            2 -> "배경음악 음량을 조절합니다 (0% ~ 100%)"
            3 -> "낮을수록 CPU 사용량 감소, 높을수록 화면이 부드럽습니다"
            4 -> "화면 찢김 방지. FPS 제한과 함께 사용 시 입력 지연이 생길 수 있습니다"
            5 -> "멀티플레이어에서 표시될 이름입니다 (최대 16자)"
            else -> ""
        }
        if (descText.isNotEmpty()) g.drawStringCentered(descText, w / 2f, py + ph - 46f)

        // ── 하단 힌트 ─────────────────────────────────────────────────────────
        g.font  = hintFont
        g.renderColor = RenderColor.of(80, 68, 108)
        val hintText = if (editingNickname)
            "문자 입력: 닉네임   Backspace: 삭제   Enter / Esc: 완료"
        else
            "↑↓ 선택   ←→ 변경   Shift+←→ 세밀 보정(±1ms)   Enter / Esc 저장 후 돌아가기"
        g.drawStringCentered(hintText, w / 2f, py + ph - 22f)
    }

    // ── 입력 ─────────────────────────────────────────────────────────────────
    override fun keyPressed(key: Int, mods: Int) {
        // 닉네임 편집 모드: 내비게이션 키를 가로챔
        if (editingNickname) {
            when (key) {
                Keys.BACKSPACE -> if (localNickname.isNotEmpty()) localNickname = localNickname.dropLast(1)
                Keys.ENTER, Keys.ESCAPE -> editingNickname = false
            }
            return
        }
        val shift = Keys.isShift(mods)
        when (key) {
            Keys.UP    -> cursor = (cursor - 1 + items.size) % items.size
            Keys.DOWN  -> cursor = (cursor + 1) % items.size
            Keys.LEFT  -> changeValue(cursor, -1, shift)
            Keys.RIGHT -> changeValue(cursor, +1, shift)
            Keys.ENTER -> if (cursor == 5) editingNickname = true else applyAndBack()
            Keys.ESCAPE -> applyAndBack()
        }
    }

    override fun keyTyped(codepoint: Int) {
        if (editingNickname && localNickname.length < 16) {
            val ch = codepoint.toChar()
            if (!ch.isISOControl()) localNickname += ch
        }
    }

    override fun mousePressed(x: Float, y: Float, button: Int, mods: Int) {
        updateHover(x, y)
        if (button == Keys.MOUSE_LEFT) {
            val pw = 720; val ph = 600
            val px = (1280 - pw) / 2; val py = (720 - ph) / 2
            val itemStartY = py + 72 + 36; val rowH = 76
            val rowY = itemStartY + 2 * rowH
            val sliderX = px + pw - 300f
            val sliderW = 150f
            if (y in rowY.toFloat()..(rowY + rowH).toFloat() && x in sliderX..(sliderX + sliderW)) {
                volumeDragActive = true
                cursor = 2
                localVolume = ((x - sliderX) / sliderW).coerceIn(0f, 1f)
                ctx.videoBackground.setTargetVolumePercent((localVolume * 100).toInt())
            }
        }
    }

    override fun mouseReleased(x: Float, y: Float, button: Int, mods: Int) {
        volumeDragActive = false
    }

    override fun mouseDragged(x: Float, y: Float, button: Int) {
        updateHover(x, y)
        if (volumeDragActive) {
            val pw = 720
            val px = (1280 - pw) / 2
            val sliderX = px + pw - 300f
            val sliderW = 150f
            localVolume = ((x - sliderX) / sliderW).coerceIn(0f, 1f)
            ctx.videoBackground.setTargetVolumePercent((localVolume * 100).toInt())
        }
    }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        updateHover(x, y)
        val pw = 720; val ph = 650
        val px = (1280 - pw) / 2; val py = (720 - ph) / 2

        if (x < px || x > px + pw || y < py || y > py + ph) { applyAndBack(); return }

        val itemStartY = py + 72 + 36; val rowH = 76
        items.forEachIndexed { i, _ ->
            val rowY = itemStartY + i * rowH
            if (y < rowY || y > rowY + rowH - 1) return@forEachIndexed
            cursor = i

            if (i == 2) {
                val sliderX = px + pw - 300f
                val sliderW = 150f
                if (x in sliderX..(sliderX + sliderW)) {
                    localVolume = ((x - sliderX) / sliderW).coerceIn(0f, 1f)
                    ctx.videoBackground.setTargetVolumePercent((localVolume * 100).toInt())
                    return@forEachIndexed
                }
            }

            val centerX = 1280 / 2f
            if (x < centerX - 20) changeValue(i, -1, false)
            else if (x > centerX + 20) changeValue(i, +1, false)
        }
    }

    private fun changeValue(idx: Int, dir: Int, shift: Boolean) {
        when (idx) {
            0 -> { val n = (modes.indexOf(localMode) + dir + modes.size) % modes.size; localMode = modes[n] }
            1 -> localOffset += if (shift) dir.toLong() else dir.toLong() * 10L
            2 -> {
                val step = if (shift) 0.01f else 0.10f
                localVolume = (localVolume + dir * step).coerceIn(0.0f, 1.0f)
                ctx.videoBackground.setTargetVolumePercent((localVolume * 100).toInt())
            }
            3 -> { val n = (fpsOptions.indexOf(localFps) + dir + fpsOptions.size) % fpsOptions.size; localFps = fpsOptions[n] }
            4 -> localVSync = !localVSync
            5 -> { /* 닉네임은 Enter로 편집 */ }
        }
    }

    private fun updateHover(x: Float, y: Float) {
        val pw = 720; val ph = 650
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
        if (editingNickname) { editingNickname = false; return }
        val modeChanged = (localMode != AppSettings.windowMode)
        AppSettings.windowMode          = localMode
        AppSettings.calibrationOffsetMs = localOffset
        AppSettings.musicVolume         = localVolume
        AppSettings.targetFps           = localFps
        AppSettings.nickname            = localNickname.trim().ifEmpty { "Player" }
        if (ctx.gameLoop.targetFPS != localFps) ctx.gameLoop.targetFPS = localFps
        if (localVSync != AppSettings.vSync) ctx.windowManager.applyVSync(localVSync)
        ctx.sceneRouter.navigate(previous)
        if (modeChanged) ctx.windowManager.applyMode(localMode)
    }
}
