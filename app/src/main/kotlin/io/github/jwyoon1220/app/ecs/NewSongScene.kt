package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.Song
import io.github.jwyoon1220.core.song.ChartParser
import io.github.jwyoon1220.engine.ImGuiRenderable
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderColor
import io.github.jwyoon1220.engine.render.RenderCommand
import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 새 곡 만들기 화면 (ECS Scene + Dear ImGui 파일 브라우저).
 *
 * 순수 UI/폼 화면이라 도메인 엔터티 없이 Scene 백본의 update/render 경로만 사용합니다.
 */
class NewSongScene(
    private val ctx: GameContext
) : Scene(), ImGuiRenderable {

    // ── 폰트 ─────────────────────────────────────────────────────────────────
    private val titleFont  = FontLoader.bold(22f)
    private val labelFont  = FontLoader.semiBold(13f)
    private val inputFont  = FontLoader.regular(14f)
    private val hintFont   = FontLoader.light(11f)
    private val btnFont    = FontLoader.semiBold(15f)

    // ── 입력 필드 데이터 ──────────────────────────────────────────────────────
    private data class Field(val label: String, var value: String = "", val hint: String = "")

    private val fTitle  = Field("제목 *",      "",       "곡 이름 (필수)")
    private val fArtist = Field("아티스트",     "",       "아티스트명")
    private val fBpm    = Field("BPM",          "",       "예: 120")
    private val fDiffs  = mutableListOf("easy", "normal", "hard")

    private var coverFile: File? = null
    private var audioFile: File? = null
    private var videoFile: File? = null

    private val fields = listOf(fTitle, fArtist, fBpm)
    private var focusedField = 0   // 0=title,1=artist,2=bpm

    // ── UI 상태 ───────────────────────────────────────────────────────────────
    private var statusMsg  = ""
    private var statusOk   = false
    private var mouseX     = 0f
    private var mouseY     = 0f
    private var hoverDiff  = -1
    private var time       = 0.0

    private data class FileBrowserEntry(val file: File, val displayName: String, val isDirectory: Boolean)
    private var pickerOpen = false
    private var pickerTitle = ""
    private var pickerDir: File = File(System.getProperty("user.dir"))
    private var pickerEntries: List<FileBrowserEntry> = emptyList()
    private var pickerSelected: File? = null
    private var pickerExts: Set<String> = emptySet()
    private var pickerOnPick: ((File) -> Unit)? = null

    // ── 레이아웃 상수 ─────────────────────────────────────────────────────────
    private val CX  = 640   // 중앙 x
    private val BOX_W = 500
    private val BOX_X get() = CX - BOX_W / 2
    private val FIELD_H = 34
    private val FIELD_GAP = 14

    override fun enter() {
        super.enter()
        ctx.inputManager.clearEvents()
        register(NewSongRenderSystem())
    }
    override fun exit()  { ctx.inputManager.clearEvents(); super.exit() }
    override fun onUpdate(deltaTime: Double) { time += deltaTime }

    private inner class NewSongRenderSystem : RenderProducer {
        override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit
        override fun produce(world: World, out: MutableList<RenderCommand>) {
            out.add(RenderCommand.LegacyDrawContext { renderContents(this) })
        }
    }

    private fun renderContents(g: io.github.jwyoon1220.engine.DrawContext) {
        val w = g.clipBounds.width; val h = g.clipBounds.height

        // ── 배경 그라디언트 ──────────────────────────────────────────────────
        g.renderColor = RenderColor.of(8, 5, 18)
        g.fillRect(0, 0, w, h)
        // 은은한 중앙 글로우
        g.fillBoxGradientRect(
            (CX - 300).toFloat(), 40f, 600f, (h - 80).toFloat(),
            20f, 120f,
            RenderColor.of(40, 20, 80, 40), RenderColor.of(0, 0, 0, 0)
        )

        // ── 타이틀 ──────────────────────────────────────────────────────────
        g.font = titleFont; g.renderColor = RenderColor.of(200, 160, 255)
        g.drawStringCentered("✦ 새 곡 만들기", CX.toFloat(), 60f)
        g.font = hintFont; g.renderColor = RenderColor.of(100, 80, 140)
        g.drawStringCentered("B1.2.2  ·  StelLane Editor", CX.toFloat(), 80f)

        // ── 패널 배경 ────────────────────────────────────────────────────────
        g.renderColor = RenderColor.of(18, 12, 35, 220)
        g.fillRoundRect(BOX_X, 95, BOX_W, 530, 14, 14)
        g.renderColor = RenderColor.of(80, 55, 130, 100)
        g.drawRoundRect(BOX_X.toFloat(), 95f, BOX_W.toFloat(), 530f, 14f)

        var cy = 125

        // ── 텍스트 필드 ──────────────────────────────────────────────────────
        fields.forEachIndexed { idx, field ->
            drawField(g, field, idx == focusedField, BOX_X + 20, cy, BOX_W - 40, FIELD_H)
            cy += FIELD_H + FIELD_GAP
        }

        cy += 4
        g.renderColor = RenderColor.of(50, 38, 78); g.drawLine(BOX_X + 16, cy, BOX_X + BOX_W - 16, cy)
        cy += 14

        // ── 파일 선택 버튼들 ─────────────────────────────────────────────────
        fun fileRow(label: String, file: File?, btnId: Int) {
            g.font = labelFont; g.renderColor = RenderColor.of(150, 120, 200)
            g.drawString(label, (BOX_X + 20).toFloat(), (cy + 13).toFloat())

            val name = file?.name ?: "선택 안 됨"
            g.font = hintFont
            g.renderColor = if (file != null) RenderColor.of(160, 230, 160) else RenderColor.of(100, 80, 130)
            g.drawString(name, (BOX_X + 115).toFloat(), (cy + 13).toFloat())

            val btnX = BOX_X + BOX_W - 80; val btnY = cy
            val hovering = mouseX in btnX.toFloat()..(btnX + 70f) && mouseY in btnY.toFloat()..(btnY + 26f)
            g.renderColor = if (hovering) RenderColor.of(90, 60, 150, 220) else RenderColor.of(55, 38, 90, 180)
            g.fillRoundRect(btnX, btnY, 70, 26, 6, 6)
            g.renderColor = RenderColor.of(150, 110, 220)
            g.drawRoundRect(btnX.toFloat(), btnY.toFloat(), 70f, 26f, 6f)
            g.font = hintFont; g.renderColor = RenderColor.WHITE
            g.drawStringCentered("탐색…", (btnX + 35).toFloat(), (btnY + 17).toFloat())
            cy += 32
        }
        fileRow("커버 이미지", coverFile, 0)
        fileRow("오디오 파일", audioFile, 1)
        fileRow("비디오 파일", videoFile, 2)

        cy += 4
        g.renderColor = RenderColor.of(50, 38, 78); g.drawLine(BOX_X + 16, cy, BOX_X + BOX_W - 16, cy)
        cy += 14

        // ── 난이도 목록 ──────────────────────────────────────────────────────
        g.font = labelFont; g.renderColor = RenderColor.of(150, 120, 200)
        g.drawString("난이도", (BOX_X + 20).toFloat(), (cy + 2).toFloat()); cy += 20

        hoverDiff = -1
        fDiffs.forEachIndexed { idx, diff ->
            val dy = cy + idx * 28
            val hovering = mouseX in (BOX_X + 18f)..(BOX_X + BOX_W - 18f) && mouseY in dy.toFloat()..(dy + 24f)
            if (hovering) hoverDiff = idx
            g.renderColor = if (hovering) RenderColor.of(55, 38, 90, 200) else RenderColor.of(30, 20, 55, 160)
            g.fillRoundRect(BOX_X + 18, dy, BOX_W - 56, 24, 4, 4)
            g.font = inputFont; g.renderColor = RenderColor.of(200, 180, 240)
            g.drawString(diff, (BOX_X + 28).toFloat(), (dy + 17).toFloat())
            // 삭제 버튼
            val dBtnX = BOX_X + BOX_W - 36; val dBtnY = dy + 3
            val delHover = mouseX in dBtnX.toFloat()..(dBtnX + 18f) && mouseY in dBtnY.toFloat()..(dBtnY + 18f)
            g.renderColor = if (delHover) RenderColor.of(200, 80, 80) else RenderColor.of(120, 60, 60, 160)
            g.fillRoundRect(dBtnX, dBtnY, 18, 18, 4, 4)
            g.font = hintFont; g.renderColor = RenderColor.WHITE
            g.drawStringCentered("✕", (dBtnX + 9).toFloat(), (dBtnY + 13).toFloat())
        }
        cy += fDiffs.size * 28 + 6

        // 난이도 추가 버튼
        val addHover = mouseX in (BOX_X + 18f)..(BOX_X + 90f) && mouseY in cy.toFloat()..(cy + 24f)
        g.renderColor = if (addHover) RenderColor.of(55, 90, 55, 200) else RenderColor.of(30, 55, 30, 150)
        g.fillRoundRect(BOX_X + 18, cy, 72, 24, 4, 4)
        g.renderColor = RenderColor.of(100, 200, 100, 180)
        g.drawRoundRect((BOX_X + 18).toFloat(), cy.toFloat(), 72f, 24f, 4f)
        g.font = hintFont; g.renderColor = RenderColor.of(140, 230, 140)
        g.drawStringCentered("+ 추가", (BOX_X + 54).toFloat(), (cy + 16).toFloat())
        cy += 36

        // ── 상태 메시지 ──────────────────────────────────────────────────────
        if (statusMsg.isNotEmpty()) {
            g.font = hintFont
            g.renderColor = if (statusOk) RenderColor.of(100, 230, 130) else RenderColor.of(255, 110, 110)
            g.drawStringCentered(statusMsg, CX.toFloat(), (BOX_X + 600).toFloat().coerceAtLeast((cy + 14).toFloat()))
        }

        // ── 하단 버튼 ────────────────────────────────────────────────────────
        val btY = 95 + 540 - 10
        val cancelHov = mouseX in (BOX_X + 20f)..(BOX_X + 140f) && mouseY in btY.toFloat()..(btY + 36f)
        g.renderColor = if (cancelHov) RenderColor.of(65, 45, 100, 220) else RenderColor.of(40, 28, 65, 180)
        g.fillRoundRect(BOX_X + 20, btY, 120, 36, 8, 8)
        g.renderColor = RenderColor.of(120, 90, 180); g.drawRoundRect((BOX_X + 20).toFloat(), btY.toFloat(), 120f, 36f, 8f)
        g.font = btnFont; g.renderColor = RenderColor.of(200, 175, 240)
        g.drawStringCentered("취소", (BOX_X + 80).toFloat(), (btY + 24).toFloat())

        val createHov = mouseX in (BOX_X + BOX_W - 140f)..(BOX_X + BOX_W - 20f) && mouseY in btY.toFloat()..(btY + 36f)
        val pulse = (0.85f + 0.15f * kotlin.math.sin(time * 2.5).toFloat()).coerceIn(0f, 1f)
        g.renderColor = if (createHov) RenderColor.of(120, 70, 200, 230) else RenderColor.of((70 * pulse).toInt(), (40 * pulse).toInt(), (140 * pulse).toInt(), 200)
        g.fillRoundRect(BOX_X + BOX_W - 140, btY, 120, 36, 8, 8)
        g.renderColor = if (createHov) RenderColor.of(200, 150, 255) else RenderColor.of(140, 100, 220)
        g.drawRoundRect((BOX_X + BOX_W - 140).toFloat(), btY.toFloat(), 120f, 36f, 8f)
        g.font = btnFont; g.renderColor = RenderColor.WHITE
        g.drawStringCentered("Create ✦", (BOX_X + BOX_W - 80).toFloat(), (btY + 24).toFloat())

        // ESC 힌트
        g.font = hintFont; g.renderColor = RenderColor.of(70, 58, 100)
        g.drawStringCentered("Esc: 취소  ·  Tab: 다음 필드  ·  Enter: 생성", CX.toFloat(), (h - 16).toFloat())
    }

    private fun drawField(g: io.github.jwyoon1220.engine.DrawContext, field: Field, focused: Boolean, x: Int, y: Int, fw: Int, fh: Int) {
        // 배경
        g.renderColor = if (focused) RenderColor.of(30, 20, 55, 220) else RenderColor.of(20, 14, 38, 190)
        g.fillRoundRect(x, y, fw, fh, 6, 6)
        g.renderColor = if (focused) RenderColor.of(140, 90, 230, 220) else RenderColor.of(60, 45, 95, 160)
        g.drawRoundRect(x.toFloat(), y.toFloat(), fw.toFloat(), fh.toFloat(), 6f)

        // 라벨
        g.font = hintFont; g.renderColor = RenderColor.of(120, 95, 175)
        g.drawString(field.label, (x + 8).toFloat(), (y + fh / 2 - 4).toFloat())

        // 값
        val displayText = field.value + if (focused && (System.currentTimeMillis() % 1000) < 500) "|" else ""
        g.font = inputFont
        g.renderColor = if (field.value.isEmpty() && !focused) RenderColor.of(60, 50, 85) else RenderColor.of(230, 215, 255)
        val textX = x + 100
        g.drawString(if (field.value.isEmpty() && !focused) field.hint else displayText, textX.toFloat(), (y + fh / 2 + 6).toFloat())
    }

    override fun keyPressed(key: Int, mods: Int) {
        if (pickerOpen && key == Keys.ESCAPE) {
            pickerOpen = false
            return
        }
        when {
            key == Keys.ESCAPE -> ctx.sceneRouter.navigate(SongSelectScene(ctx, SelectMode.EDIT))
            key == Keys.TAB    -> focusedField = (focusedField + 1) % fields.size
            key == Keys.ENTER  -> tryCreate()
            key == Keys.BACKSPACE -> {
                val f = fields[focusedField]
                if (f.value.isNotEmpty()) f.value = f.value.dropLast(1)
            }
        }
    }

    override fun keyTyped(codepoint: Int) {
        if (codepoint < 32 || codepoint == 127) return
        fields[focusedField].value += codepoint.toChar()
    }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        val mx = x.toInt(); val my = y.toInt()

        // 필드 포커스
        fields.forEachIndexed { idx, _ ->
            val fy = 125 + idx * (FIELD_H + FIELD_GAP)
            if (x in BOX_X.toFloat()..(BOX_X + BOX_W).toFloat() && y in fy.toFloat()..(fy + FIELD_H).toFloat()) {
                focusedField = idx
            }
        }

        // 파일 선택 버튼
        val fileRowsY = listOf(
            125 + fields.size * (FIELD_H + FIELD_GAP) + 22,
            125 + fields.size * (FIELD_H + FIELD_GAP) + 54,
            125 + fields.size * (FIELD_H + FIELD_GAP) + 86
        )
        fileRowsY.forEachIndexed { idx, ry ->
            val btnX = BOX_X + BOX_W - 80
            if (mx in btnX..(btnX + 70) && my in ry..(ry + 26)) {
                when (idx) {
                    0 -> openPicker("커버 이미지 선택", "png,jpg,jpeg,webp") { coverFile = it }
                    1 -> openPicker("오디오 파일 선택", "mp3,ogg,wav,flac,m4a") { audioFile = it }
                    2 -> openPicker("비디오 파일 선택", "mp4,mkv,avi,mov,webm") { videoFile = it }
                }
            }
        }

        // 난이도 삭제 버튼
        val diffBaseY = 125 + fields.size * (FIELD_H + FIELD_GAP) + 130
        fDiffs.forEachIndexed { idx, _ ->
            val dy = diffBaseY + idx * 28
            val dBtnX = BOX_X + BOX_W - 36; val dBtnY = dy + 3
            if (mx in dBtnX..(dBtnX + 18) && my in dBtnY..(dBtnY + 18)) {
                fDiffs.removeAt(idx); return
            }
        }

        // 난이도 추가
        val addY = diffBaseY + fDiffs.size * 28 + 6
        if (mx in (BOX_X + 18)..(BOX_X + 90) && my in addY..(addY + 24)) {
            fDiffs.add("diff${fDiffs.size + 1}")
        }

        // 취소
        val btY = 95 + 540 - 10
        if (mx in (BOX_X + 20)..(BOX_X + 140) && my in btY..(btY + 36)) {
            ctx.sceneRouter.navigate(SongSelectScene(ctx, SelectMode.EDIT))
        }
        // Create
        if (mx in (BOX_X + BOX_W - 140)..(BOX_X + BOX_W - 20) && my in btY..(btY + 36)) {
            tryCreate()
        }
    }

    override fun mouseDragged(x: Float, y: Float, button: Int) { mouseX = x; mouseY = y }

    override fun mouseScrolled(dy: Double) {}

    override fun mousePressed(x: Float, y: Float, button: Int, mods: Int) { mouseX = x; mouseY = y }
    override fun mouseReleased(x: Float, y: Float, button: Int, mods: Int) {}

    private fun openPicker(title: String, extensions: String, onPick: (File) -> Unit) {
        pickerTitle = title
        pickerExts = extensions.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        pickerDir = coverFile?.parentFile ?: audioFile?.parentFile ?: videoFile?.parentFile
            ?: File(ctx.songManager.workingDir, "songs").takeIf { it.exists() }
            ?: File(System.getProperty("user.dir"))
        pickerSelected = null
        pickerOnPick = onPick
        refreshPickerEntries()
        pickerOpen = true
    }

    private fun refreshPickerEntries() {
        val dir = pickerDir.takeIf { it.exists() && it.isDirectory } ?: return
        pickerEntries = dir.listFiles().orEmpty()
            .filter { it.isDirectory || it.extension.lowercase() in pickerExts }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map {
                FileBrowserEntry(
                    file = it,
                    displayName = if (it.isDirectory) "[DIR] ${it.name}" else it.name,
                    isDirectory = it.isDirectory
                )
            }
    }

    override fun renderImGui() {
        if (!pickerOpen) return

        ImGui.setNextWindowSize(560f, 430f)
        if (ImGui.begin(pickerTitle, ImGuiWindowFlags.NoCollapse)) {
            ImGui.textWrapped("내부 파일 브라우저")
            ImGui.separator()
            ImGui.textWrapped(pickerDir.absolutePath)

            if (ImGui.button("상위 폴더")) {
                pickerDir.parentFile?.takeIf { it.exists() && it.isDirectory }?.let {
                    pickerDir = it
                    pickerSelected = null
                    refreshPickerEntries()
                }
            }
            ImGui.sameLine()
            if (ImGui.button("songs 폴더")) {
                val songsDir = File(ctx.songManager.workingDir, "songs")
                if (songsDir.exists() && songsDir.isDirectory) {
                    pickerDir = songsDir
                    pickerSelected = null
                    refreshPickerEntries()
                }
            }
            ImGui.sameLine()
            if (ImGui.button("새로고침")) refreshPickerEntries()

            ImGui.separator()
            ImGui.beginChild("NewSongPickerList", 0f, 270f, true)
            if (pickerEntries.isEmpty()) {
                ImGui.textWrapped("선택 가능한 파일이 없습니다.")
            } else {
                for (entry in pickerEntries) {
                    val isSelected = pickerSelected?.absolutePath == entry.file.absolutePath
                    if (ImGui.selectable(entry.displayName, isSelected)) {
                        if (entry.isDirectory) {
                            pickerDir = entry.file
                            pickerSelected = null
                            refreshPickerEntries()
                        } else {
                            pickerSelected = entry.file
                        }
                    }
                }
            }
            ImGui.endChild()

            ImGui.textWrapped(pickerSelected?.name ?: "선택된 파일 없음")
            if (ImGui.button("선택", 120f, 30f)) {
                pickerSelected?.let { file -> pickerOnPick?.invoke(file); pickerOpen = false }
            }
            ImGui.sameLine()
            if (ImGui.button("취소", 120f, 30f)) pickerOpen = false
            ImGui.end()
        }
    }

    // ── 생성 로직 ─────────────────────────────────────────────────────────────
    private fun tryCreate() {
        val title = fTitle.value.trim()
        if (title.isEmpty()) { statusMsg = "제목은 필수입니다."; statusOk = false; focusedField = 0; return }
        if (fDiffs.isEmpty()) { statusMsg = "난이도가 최소 1개 필요합니다."; statusOk = false; return }

        runCatching { createSong(title) }
            .onSuccess { statusMsg = "'$title' 생성 완료!"; statusOk = true
                ctx.songManager.refresh()
                ctx.sceneRouter.navigate(SongSelectScene(ctx, SelectMode.EDIT))
            }
            .onFailure { e -> statusMsg = "오류: ${e.message}"; statusOk = false }
    }

    private fun createSong(title: String) {
        val songsDir   = File(ctx.songManager.workingDir, "songs").also { it.mkdirs() }
        val folderName = sanitize(title)
        val songDir    = File(songsDir, folderName).also { it.mkdirs() }
        val metaFile   = File(songsDir, "$folderName.json")

        fun copyTo(src: File?, destName: String): String? {
            src ?: return null
            val dest = File(songDir, destName)
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return destName
        }

        val coverName = coverFile?.let { copyTo(it, "cover.${it.extension}") }
        val audioName = audioFile?.let { copyTo(it, "audio.${it.extension}") }
        val videoName = videoFile?.let { copyTo(it, "video.${it.extension}") }

        val difficulties = mutableMapOf<String, String>()
        fDiffs.forEach { diff ->
            val fn = "$diff.json"
            difficulties[diff] = fn
            val cf = File(songDir, fn)
            if (!cf.exists()) ChartParser.serializeChart(Chart(0L, emptyList()), cf)
        }

        ChartParser.serializeSong(
            Song(
                title          = title,
                artist         = fArtist.value.trim(),
                bpm            = fBpm.value.trim().toIntOrNull(),
                coverImagePath = coverName,
                audioPath      = audioName,
                videoPath      = videoName,
                difficulties   = difficulties
            ),
            metaFile
        )
    }

    private fun sanitize(s: String) =
        s.replace(Regex("[^a-zA-Z0-9가-힣\\-_ ]"), "_").trim().replace(" ", "_").take(64).ifEmpty { "untitled" }
}
