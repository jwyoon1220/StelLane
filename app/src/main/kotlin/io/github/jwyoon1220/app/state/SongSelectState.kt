package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.ui.SongZipUtil
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.song.ChartParser
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.GameState
import io.github.jwyoon1220.engine.ImGuiRenderable
import io.github.jwyoon1220.engine.Keys
import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImString
import org.slf4j.LoggerFactory
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*

enum class SelectMode { PLAY, EDIT }

class SongSelectState(
    private val ctx: GameContext,
    private val mode: SelectMode
) : GameState, ImGuiRenderable {

    private val log = LoggerFactory.getLogger(SongSelectState::class.java)

    private var songIndex = 0
    private var diffIndex = 0

    // ── 레이아웃 ────────────────────────────────────────────────────────────
    private val PANEL_W  = 340
    private val PAD      = 28
    private val COVER_S  = 256
    private val ROW_H    = 80
    private val THUMB_S  = 56
    private val HEADER_H = 52

    // ── 폰트 ────────────────────────────────────────────────────────────────
    private val headerFont   = FontLoader.semiBold(15f)
    private val titleBigFont = FontLoader.bold(20f)
    private val artistFont   = FontLoader.regular(13f)
    private val metaFont     = FontLoader.light(12f)
    private val diffFont     = FontLoader.semiBold(13f)
    private val rowTitleFont = FontLoader.semiBold(15f)
    private val rowArtFont   = FontLoader.regular(12f)
    private val hintFont     = FontLoader.light(11f)
    private val emptyFont    = FontLoader.regular(16f)

    // ── 커버 이미지 캐시 (LRU 최대 20곡) ───────────────────────────────────
    private val imageCache = object : LinkedHashMap<String, BufferedImage?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, BufferedImage?>) = size > 20
    }

    // ── 비디오 프리뷰 ────────────────────────────────────────────────────────
    private val PREVIEW_START_MS = 20_000L
    private var lastPreviewSong: String? = null

    // ── 애니메이션 ───────────────────────────────────────────────────────────
    private var time       = 0.0
    private var scrollAnim = 0f

    // ── 마우스 ───────────────────────────────────────────────────────────────
    private var mouseX  = 0f
    private var mouseY  = 0f
    private var hoverIdx= -1

    @Volatile private var exportInProgress = false
    @Volatile private var exportMessage = ""

    private data class FileBrowserEntry(val file: File, val displayName: String, val isDirectory: Boolean)
    private var exportDialogOpen = false
    private var exportDialogDir: File = ctx.songManager.workingDir
    private var exportDialogEntries: List<FileBrowserEntry> = emptyList()
    private var exportDialogSelected: File? = null
    private val exportFileName = ImString(256)

    private val songs    get() = ctx.songManager.songs
    private val curSong  get() = songs.getOrNull(songIndex)
    private val curDiffs get() = curSong?.song?.difficulties?.keys?.toList() ?: emptyList()

    override fun enter() {
        ctx.songManager.refresh()
        log.info("SongSelectState enter mode={} songs={}", mode, ctx.songManager.songs.size)
        songIndex = 0; diffIndex = 0; lastPreviewSong = null; time = 0.0
        ctx.inputManager.clearEvents()
        playPreviewForCurrent()
    }

    override fun exit() {
        ctx.videoBackground.onFinished       = null
        ctx.videoBackground.onPlayingStarted = null
        ctx.videoBackground.stop()
        lastPreviewSong = null
    }

    private fun playPreviewForCurrent() {
        val entry  = curSong ?: run { ctx.videoBackground.stop(); return }
        val songId = entry.metaFile.absolutePath
        if (songId == lastPreviewSong) return
        lastPreviewSong = songId
        val videoPath = entry.song.videoPath?.let { File(entry.songDir, it) }?.takeIf { it.exists() }
        if (videoPath == null) { ctx.videoBackground.stop(); return }
        ctx.videoBackground.onFinished       = { ctx.videoBackground.seek(PREVIEW_START_MS) }
        ctx.videoBackground.onPlayingStarted = { ctx.videoBackground.seek(PREVIEW_START_MS) }
        ctx.videoBackground.play(videoPath.absolutePath)
    }

    override fun update(deltaTime: Double) {
        time += deltaTime
        val targetY = songIndex * ROW_H.toFloat()
        scrollAnim += (targetY - scrollAnim) * (1f - exp(-deltaTime.toFloat() * 12f))
    }

    private fun getCoverImage(entry: SongEntry): BufferedImage? {
        val path = entry.song.coverImagePath ?: return null
        val key  = entry.metaFile.absolutePath
        return imageCache.getOrPut(key) {
            runCatching { ImageIO.read(File(entry.songDir, path)) }.getOrNull()
        }
    }

    override fun render(g: DrawContext) {
        val w = g.clipBounds.width
        val h = g.clipBounds.height
        val t = time.toFloat()

        // ── 전체 어두운 오버레이 ─────────────────────────────────────────────
        val old = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.88f)
        g.color = Color(8, 5, 18)
        g.fillRect(0, 0, w, h)
        g.composite = old

        // ── 왼쪽 상세 패널 배경 ──────────────────────────────────────────────
        g.fillLinearGradient(
            0f, 0f, PANEL_W.toFloat(), h.toFloat(),
            0f, 0f, PANEL_W.toFloat(), 0f,
            Color(22, 14, 44, 250), Color(12, 8, 28, 250)
        )
        // 구분선 (그라디언트)
        g.fillLinearGradient(
            (PANEL_W - 1).toFloat(), 0f, 2f, h.toFloat(),
            (PANEL_W - 1).toFloat(), 0f, (PANEL_W + 1).toFloat(), 0f,
            Color(100, 60, 180, 160), Color(40, 20, 80, 60)
        )

        // ── 헤더 바 ──────────────────────────────────────────────────────────
        g.color = Color(16, 10, 34, 240)
        g.fillRect(0, 0, w, HEADER_H)
        g.color = Color(50, 30, 90, 180)
        g.drawLine(0, HEADER_H, w, HEADER_H)

        // 모드 레이블
        val modeLabel = if (mode == SelectMode.PLAY) "▶  PLAY" else "✏  EDIT"
        g.font  = headerFont
        g.color = Color(180, 130, 255)
        g.drawString(modeLabel, PAD.toFloat(), 33f)

        // 곡 수
        if (songs.isNotEmpty()) {
            g.font  = metaFont
            g.color = Color(80, 65, 110)
            g.drawStringRight("${songs.size} songs", (PANEL_W - PAD).toFloat(), 33f)
        }

        // 힌트 (헤더 우측)
        val hint = if (mode == SelectMode.EDIT)
            "↑↓  탐색   Enter  선택   N  새 곡   E  내보내기   Esc  뒤로"
        else
            "↑↓  탐색   ←→  난이도   Enter  선택   Esc  뒤로"
        g.font  = hintFont
        g.color = Color(65, 55, 90)
        g.drawString(hint, (w / 2 + 60).toFloat(), 33f)
        if (exportMessage.isNotEmpty()) {
            g.font = hintFont
            g.color = if (exportInProgress) Color(220, 190, 120) else Color(120, 200, 140)
            g.drawString(exportMessage, (w / 2 + 60).toFloat(), 48f)
        }

        // ── 왼쪽: 선택된 곡 상세 ─────────────────────────────────────────────
        curSong?.let { entry ->
            val coverX = (PANEL_W - COVER_S) / 2
            val coverY = HEADER_H + 24

            val cover = getCoverImage(entry)
            if (cover != null) {
                g.scoped {
                    setClip(coverX.toFloat(), coverY.toFloat(), COVER_S.toFloat(), COVER_S.toFloat())
                    val pscale = 1f + sin(t * 0.6f).toFloat() * 0.005f
                    val ox = coverX + COVER_S / 2f * (1 - pscale)
                    val oy = coverY + COVER_S / 2f * (1 - pscale)
                    drawImage(cover, ox.toInt(), oy.toInt(),
                        (COVER_S * pscale).toInt(), (COVER_S * pscale).toInt(), null)
                }
                // 커버 테두리 글로우
                g.fillBoxGradientRect(
                    coverX.toFloat(), coverY.toFloat(), COVER_S.toFloat(), COVER_S.toFloat(),
                    10f, 20f,
                    Color(140, 80, 255, 0), Color(0, 0, 0, 0)
                )
            } else {
                g.fillLinearGradient(
                    coverX.toFloat(), coverY.toFloat(), COVER_S.toFloat(), COVER_S.toFloat(),
                    coverX.toFloat(), coverY.toFloat(), coverX.toFloat(), (coverY + COVER_S).toFloat(),
                    Color(38, 22, 68), Color(22, 12, 42)
                )
                g.color = Color(80, 55, 120)
                g.font  = FontLoader.bold(52f)
                g.drawStringCentered("♪", PANEL_W / 2f, coverY + COVER_S / 2f + 18f)
            }

            // 곡 정보
            var ty = coverY + COVER_S + 22f

            g.font  = titleBigFont
            g.color = Color(235, 225, 255)
            g.drawStringCentered(entry.song.title, PANEL_W / 2f, ty); ty += 26f

            g.font  = artistFont
            g.color = Color(150, 125, 200)
            g.drawStringCentered(entry.song.artist, PANEL_W / 2f, ty); ty += 20f

            entry.song.bpm?.let {
                g.font  = metaFont
                g.color = Color(80, 65, 110)
                g.drawStringCentered("BPM  $it", PANEL_W / 2f, ty); ty += 18f
            }

            // 난이도 선택
            if (curDiffs.isNotEmpty()) {
                ty += 6f
                val diff = curDiffs.getOrNull(diffIndex) ?: ""
                val btnW = 180f; val btnH = 34f
                val btnX = (PANEL_W - btnW) / 2f; val btnY = ty - 22f
                g.color = Color(50, 30, 90, 160)
                g.fillRoundRect(btnX, btnY, btnW, btnH, 17f)
                g.color = Color(100, 60, 180, 120)
                g.drawRoundRect(btnX, btnY, btnW, btnH, 17f)
                g.font  = diffFont
                g.color = Color(220, 185, 80)
                g.drawStringCentered("◀  $diff  ▶", PANEL_W / 2f, ty)
            }
        }

        // 곡이 없을 때
        if (songs.isEmpty()) {
            g.font  = emptyFont
            g.color = Color(100, 85, 140)
            g.drawStringCentered("No songs", PANEL_W / 2f, h / 2f)
            g.font  = hintFont
            g.color = Color(65, 55, 90)
            g.drawStringCentered("N 를 눌러 새 곡 만들기", PANEL_W / 2f, h / 2f + 24f)
        }

        // ── 오른쪽: 스크롤 목록 ─────────────────────────────────────────────
        val listX  = PANEL_W + 1
        val listW  = w - listX
        val topPad = HEADER_H
        val areaH  = h - topPad - 28

        g.scoped {
            setClip(listX, topPad, listW, areaH)

            val anchorY = topPad + areaH / 4
            val baseOff = anchorY - scrollAnim.toInt()

            for (i in songs.indices) {
                val rowY = baseOff + i * ROW_H
                if (rowY + ROW_H < topPad || rowY > topPad + areaH) continue

                val entry    = songs[i]
                val selected = (i == songIndex)
                val hovered  = (i == hoverIdx)

                when {
                    selected -> {
                        fillLinearGradient(
                            listX.toFloat(), rowY.toFloat(), listW.toFloat(), ROW_H.toFloat(),
                            listX.toFloat(), 0f, (listX + listW).toFloat(), 0f,
                            Color(80, 45, 150, 210), Color(40, 20, 80, 60)
                        )
                        val barH = (sin(t * 2f) * 4 + ROW_H - 4).toFloat()
                        val barY = rowY + (ROW_H - barH) / 2
                        color = Color(180, 120, 255)
                        fillRoundRect(listX.toFloat(), barY, 3f, barH, 2f)
                    }
                    hovered -> {
                        color = Color(255, 255, 255, 12)
                        fillRect(listX, rowY, listW, ROW_H)
                    }
                    i % 2 == 0 -> {
                        color = Color(255, 255, 255, 4)
                        fillRect(listX, rowY, listW, ROW_H)
                    }
                }

                if (!selected) {
                    color = Color(40, 30, 60)
                    drawLine(listX + THUMB_S + 20, rowY + ROW_H - 1, listX + listW - PAD, rowY + ROW_H - 1)
                }

                // 썸네일
                val thumbX = listX + 16
                val thumbY = rowY + (ROW_H - THUMB_S) / 2
                val thumb  = getCoverImage(entry)
                if (thumb != null) {
                    scoped {
                        setClip(thumbX, thumbY, THUMB_S, THUMB_S)
                        drawImage(thumb, thumbX, thumbY, THUMB_S, THUMB_S, null)
                    }
                    color = if (selected) Color(150, 90, 255, 120) else Color(60, 45, 80, 100)
                    drawRoundRect(thumbX.toFloat(), thumbY.toFloat(), THUMB_S.toFloat(), THUMB_S.toFloat(), 5f)
                } else {
                    color = Color(30, 20, 50)
                    fillRoundRect(thumbX, thumbY, THUMB_S, THUMB_S, 5, 5)
                    color = Color(60, 45, 80)
                    drawRoundRect(thumbX.toFloat(), thumbY.toFloat(), THUMB_S.toFloat(), THUMB_S.toFloat(), 5f)
                }

                // 텍스트
                val textX  = (thumbX + THUMB_S + 16).toFloat()
                val titleY = (rowY + ROW_H / 2 - 8).toFloat()

                font  = rowTitleFont
                color = if (selected) Color(245, 235, 255) else Color(170, 158, 200)
                drawStringLeft(entry.song.title, textX, titleY)

                font  = rowArtFont
                color = if (selected) Color(170, 140, 220) else Color(90, 78, 118)
                drawStringLeft(entry.song.artist, textX, titleY + 18f)

                // 선택 시 난이도 뱃지
                if (selected && curDiffs.isNotEmpty()) {
                    val diff   = curDiffs.getOrNull(diffIndex) ?: ""
                    val badgeW = 80f; val badgeH = 18f
                    val badgeX = (listX + listW - badgeW - PAD)
                    val badgeY = (rowY + (ROW_H - badgeH) / 2)
                    font  = metaFont
                    color = Color(60, 35, 100, 180)
                    fillRoundRect(badgeX, badgeY, badgeW, badgeH, 9f)
                    color = Color(200, 160, 80)
                    drawStringCentered(diff, badgeX + badgeW / 2, badgeY + 13f)
                }
            }
        }

        // 스크롤바
        if (songs.size > 1) {
            val barH   = areaH - 8
            val thumbH = maxOf(24f, barH.toFloat() * minOf(1f, areaH.toFloat() / (songs.size * ROW_H)))
            val maxScr = maxOf(1, songs.size - 1)
            val thumbY = topPad + 4f + (barH - thumbH) * songIndex / maxScr
            g.color = Color(30, 20, 50)
            g.fillRoundRect((w - 8).toFloat(), (topPad + 4).toFloat(), 4f, barH.toFloat(), 2f)
            g.color = Color(100, 65, 160, 180)
            g.fillRoundRect((w - 8).toFloat(), thumbY, 4f, thumbH, 2f)
        }
    }

    // ── 입력 ─────────────────────────────────────────────────────────────────
    override fun keyPressed(key: Int, mods: Int) {
        if (exportDialogOpen && key == Keys.ESCAPE) {
            exportDialogOpen = false
            return
        }
        when (key) {
            Keys.UP     -> if (songs.isNotEmpty()) { songIndex = (songIndex - 1 + songs.size) % songs.size; diffIndex = 0; lastPreviewSong = null; playPreviewForCurrent() }
            Keys.DOWN   -> if (songs.isNotEmpty()) { songIndex = (songIndex + 1) % songs.size;             diffIndex = 0; lastPreviewSong = null; playPreviewForCurrent() }
            Keys.LEFT   -> if (curDiffs.isNotEmpty()) diffIndex = (diffIndex - 1 + curDiffs.size) % curDiffs.size
            Keys.RIGHT  -> if (curDiffs.isNotEmpty()) diffIndex = (diffIndex + 1) % curDiffs.size
            Keys.ENTER  -> onConfirm()
            Keys.N      -> if (mode == SelectMode.EDIT) ctx.stateManager.changeState(NewSongState(ctx))
            Keys.E      -> if (mode == SelectMode.EDIT) openExportDialog()
            Keys.ESCAPE -> ctx.stateManager.changeState(MainMenuState(ctx))
        }
    }

    override fun mouseDragged(x: Float, y: Float, button: Int) { mouseX = x; mouseY = y; updateHover(x, y) }
    override fun mousePressed(x: Float, y: Float, button: Int, mods: Int) { mouseX = x; mouseY = y; updateHover(x, y) }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        mouseX = x; mouseY = y; updateHover(x, y)

        // 왼쪽 패널 난이도 버튼
        if (x < PANEL_W && curDiffs.isNotEmpty()) {
            val coverY  = HEADER_H + 24
            val diffY   = coverY + COVER_S + 22f + 26f + 20f + 18f + 6f
            if (y in (diffY - 24f)..(diffY + 12f)) {
                if (x < PANEL_W / 2) diffIndex = (diffIndex - 1 + curDiffs.size) % curDiffs.size
                else                 diffIndex = (diffIndex + 1) % curDiffs.size
            }
            return
        }

        // 오른쪽 목록 클릭
        val areaTop = HEADER_H; val areaH = 720 - areaTop - 28
        val anchorY = areaTop + areaH / 4
        val baseOff = anchorY - scrollAnim.toInt()
        for (i in songs.indices) {
            val rowY = baseOff + i * ROW_H
            if (y in rowY.toFloat()..(rowY + ROW_H).toFloat()) {
                if (i == songIndex) onConfirm() else { songIndex = i; diffIndex = 0; lastPreviewSong = null; playPreviewForCurrent() }
                return
            }
        }
    }

    override fun mouseScrolled(dy: Double) {
        if (songs.isEmpty()) return
        songIndex = if (dy < 0) (songIndex + 1).coerceAtMost(songs.size - 1)
                    else        (songIndex - 1).coerceAtLeast(0)
        diffIndex = 0; lastPreviewSong = null; playPreviewForCurrent()
    }

    private fun updateHover(x: Float, y: Float) {
        if (x < PANEL_W) { hoverIdx = -1; return }
        val areaTop = HEADER_H; val areaH = 720 - areaTop - 28
        val anchorY = areaTop + areaH / 4
        val baseOff = anchorY - scrollAnim.toInt()
        hoverIdx = -1
        for (i in songs.indices) {
            val rowY = baseOff + i * ROW_H
            if (y in rowY.toFloat()..(rowY + ROW_H).toFloat()) { hoverIdx = i; break }
        }
    }

    private fun onConfirm() {
        val entry    = curSong ?: return
        val diffName = curDiffs.getOrNull(diffIndex) ?: return
        val chartFile= File(entry.songDir, entry.song.difficulties[diffName] ?: return)
        if (!chartFile.exists()) return
        val chart = runCatching { ChartParser.parseChart(chartFile) }.getOrNull() ?: return
        when (mode) {
            SelectMode.PLAY -> ctx.stateManager.changeState(PlayState(ctx, entry, chart))
            SelectMode.EDIT -> ctx.stateManager.changeState(EditorState(ctx, entry, chartFile, chart))
        }
    }

    private fun openExportDialog() {
        val entry = curSong ?: return
        exportDialogDir = ctx.songManager.workingDir.takeIf { it.exists() && it.isDirectory } ?: File(System.getProperty("user.dir"))
        exportDialogSelected = null
        exportFileName.set("${sanitizeFileName(entry.song.title)}.zip")
        refreshExportDialogEntries()
        exportDialogOpen = true
    }

    private fun exportCurrentSong(dest: File) {
        val entry = curSong ?: return
        if (exportInProgress) return

        exportInProgress = true
        exportMessage = "내보내는 중... ${dest.name}"

        Thread({
            runCatching { SongZipUtil.export(entry, dest) }
                .onSuccess {
                    exportMessage = "내보내기 완료: ${dest.name}"
                    log.info("곡 내보내기 완료: {}", dest.absolutePath)
                }
                .onFailure {
                    exportMessage = "내보내기 실패: ${it.message ?: "알 수 없는 오류"}"
                    log.error("내보내기 실패", it)
                }
            exportInProgress = false
        }, "song-export-${entry.song.title}").apply { isDaemon = true }.start()
    }

    private fun refreshExportDialogEntries() {
        val dir = exportDialogDir.takeIf { it.exists() && it.isDirectory } ?: return
        exportDialogEntries = dir.listFiles().orEmpty()
            .filter { it.isDirectory || it.extension.equals("zip", ignoreCase = true) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map {
                FileBrowserEntry(
                    file = it,
                    displayName = if (it.isDirectory) "[DIR] ${it.name}" else it.name,
                    isDirectory = it.isDirectory
                )
            }
    }

    private fun sanitizeFileName(name: String): String {
        val safe = name.replace(Regex("[^a-zA-Z0-9가-힣\\-_ ]"), "_").trim().replace(" ", "_")
        return safe.ifEmpty { "song" }.take(80)
    }

    override fun renderImGui() {
        if (!exportDialogOpen) return

        ImGui.setNextWindowSize(560f, 430f)
        if (ImGui.begin("곡 내보내기", ImGuiWindowFlags.NoCollapse)) {
            ImGui.textWrapped("내부 파일 브라우저에서 저장 위치와 파일명을 선택합니다.")
            ImGui.separator()
            ImGui.textWrapped(exportDialogDir.absolutePath)

            if (ImGui.button("상위 폴더")) {
                exportDialogDir.parentFile?.takeIf { it.exists() && it.isDirectory }?.let {
                    exportDialogDir = it
                    exportDialogSelected = null
                    refreshExportDialogEntries()
                }
            }
            ImGui.sameLine()
            if (ImGui.button("기본 경로")) {
                exportDialogDir = ctx.songManager.workingDir.takeIf { it.exists() && it.isDirectory } ?: exportDialogDir
                exportDialogSelected = null
                refreshExportDialogEntries()
            }
            ImGui.sameLine()
            if (ImGui.button("새로고침")) refreshExportDialogEntries()

            ImGui.separator()
            ImGui.beginChild("ExportFileList", 0f, 250f, true)
            if (exportDialogEntries.isEmpty()) {
                ImGui.textWrapped("표시할 폴더/zip 파일이 없습니다.")
            } else {
                for (entry in exportDialogEntries) {
                    val selected = exportDialogSelected?.absolutePath == entry.file.absolutePath
                    if (ImGui.selectable(entry.displayName, selected)) {
                        if (entry.isDirectory) {
                            exportDialogDir = entry.file
                            exportDialogSelected = null
                            refreshExportDialogEntries()
                        } else {
                            exportDialogSelected = entry.file
                            exportFileName.set(entry.file.name)
                        }
                    }
                }
            }
            ImGui.endChild()

            ImGui.inputText("파일명", exportFileName)

            if (ImGui.button("내보내기", 120f, 30f)) {
                val raw = exportFileName.get().trim()
                if (raw.isNotEmpty()) {
                    val finalName = if (raw.endsWith(".zip", ignoreCase = true)) raw else "$raw.zip"
                    exportCurrentSong(File(exportDialogDir, finalName))
                    exportDialogOpen = false
                }
            }
            ImGui.sameLine()
            if (ImGui.button("취소", 120f, 30f)) exportDialogOpen = false
            ImGui.end()
        }
    }
}
