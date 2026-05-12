package io.github.jwyoon1220.app.state

import io.github.jwyoon1220.app.FontLoader
import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.app.ui.NewSongDialog
import io.github.jwyoon1220.app.ui.SongImportDialog
import io.github.jwyoon1220.app.ui.SongZipUtil
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.song.ChartParser
import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.GameState
import io.github.jwyoon1220.engine.Keys
import org.slf4j.LoggerFactory
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

enum class SelectMode { PLAY, EDIT }

class SongSelectState(
    private val ctx: GameContext,
    private val mode: SelectMode
) : GameState {

    private val log = LoggerFactory.getLogger(SongSelectState::class.java)

    private var songIndex = 0
    private var diffIndex = 0

    // ── 레이아웃 상수 ──────────────────────────────────────────────────────────
    private val LEFT_W    = 300          // 왼쪽 고정 패널 너비
    private val PAD       = 24           // 공통 패딩
    private val COVER_MAX = 240          // 커버 이미지 최대 크기
    private val ROW_H     = 72           // 오른쪽 목록 행 높이
    private val THUMB_S   = 48           // 목록 썸네일 크기

    // ── 폰트 ──────────────────────────────────────────────────────────────────
    private val modeFont   = FontLoader.semiBold(14f)
    private val titleLFont = FontLoader.bold(22f)       // 왼쪽 패널 제목
    private val artistLFont= FontLoader.regular(15f)    // 왼쪽 패널 아티스트
    private val diffFont   = FontLoader.regular(13f)
    private val bpmFont    = FontLoader.light(13f)
    private val titleRFont = FontLoader.semiBold(16f)   // 목록 제목
    private val artistRFont= FontLoader.regular(13f)    // 목록 아티스트
    private val hintFont   = FontLoader.light(12f)

    // ── 커버 이미지 캐시 (LRU, 최대 20곡) ──────────────────────────────────────
    private val imageCache = object : LinkedHashMap<String, BufferedImage?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, BufferedImage?>) = size > 20
    }

    // ── 비디오 프리뷰 ─────────────────────────────────────────────────────────
    /** 곡당 고정 클립 시작 위치 (ms). 항상 이 지점부터 재생. */
    private val PREVIEW_START_MS = 20_000L
    private var lastPreviewSong: String? = null

    private val songs    get() = ctx.songManager.songs
    private val curSong  get() = songs.getOrNull(songIndex)
    private val curDiffs get() = curSong?.song?.difficulties?.keys?.toList() ?: emptyList()

    override fun enter() {
        ctx.songManager.refresh()
        log.info("SongSelectState enter mode={} songs={}", mode, ctx.songManager.songs.size)
        songIndex = 0
        diffIndex = 0
        lastPreviewSong = null
        ctx.inputManager.clearEvents()
        playPreviewForCurrent()
    }

    override fun exit() {
        log.info("SongSelectState exit")
        ctx.videoBackground.onFinished  = null
        ctx.videoBackground.onPlayingStarted = null
        ctx.videoBackground.stop()
        lastPreviewSong = null
    }

    private fun playPreviewForCurrent() {
        val entry = curSong ?: run { ctx.videoBackground.stop(); return }
        val songId = entry.metaFile.absolutePath
        if (songId == lastPreviewSong) return   // 이미 재생 중
        lastPreviewSong = songId

        val videoPath = entry.song.videoPath?.let { File(entry.songDir, it) }
            ?.takeIf { it.exists() }
        if (videoPath == null) {
            ctx.videoBackground.stop()
            return
        }

        ctx.videoBackground.onFinished = {
            // 클립 종료 시 동일 구간으로 나시 시작
            ctx.videoBackground.seek(PREVIEW_START_MS)
        }
        ctx.videoBackground.onPlayingStarted = {
            ctx.videoBackground.seek(PREVIEW_START_MS)
        }
        ctx.videoBackground.play(videoPath.absolutePath)
        log.info("[Preview] {}", videoPath.name)
    }

    override fun update(deltaTime: Double) {}

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

        // ── 전체 배경 (비디오 위 반투명 오버레이) ───────────────────────
        val old = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.82f)
        g.color = Color(12, 9, 22)
        g.fillRect(0, 0, w, h)
        g.composite = old

        // ── 왼쪽 고정 패널 배경 ────────────────────────────────────────────────
        g.color = Color(22, 16, 40)
        g.fillRect(0, 0, LEFT_W, h)

        // 구분선 (1px, 얇고 미묘하게)
        g.color = Color(60, 48, 90)
        g.drawLine(LEFT_W, 0, LEFT_W, h)

        // ── 모드 라벨 (좌측 상단) ─────────────────────────────────────────────
        val modeLabel = if (mode == SelectMode.PLAY) "▶  Play" else "✏  Edit"
        g.font  = modeFont
        g.color = Color(170, 130, 230)
        g.drawString(modeLabel, PAD, PAD + 14)

        // 조작 힌트 (최하단)
        g.font  = hintFont
        g.color = Color(70, 62, 95)
        val hint = if (mode == SelectMode.EDIT)
            "↑↓  탐색   Enter  선택   I  가져오기   N  새 곡   E  내보내기   Esc  뒤로"
        else
            "↑↓  탐색   ←→  난이도   Enter  선택   I  가져오기   Esc  뒤로"
        g.drawString(hint, PAD, h - 10)

        if (songs.isEmpty()) {
            g.font  = titleRFont
            g.color = Color(120, 110, 150)
            val msg = "No songs — press I to import"
            val fm  = g.getFontMetrics(titleRFont)
            g.drawString(msg, LEFT_W + (w - LEFT_W - fm.stringWidth(msg)) / 2, h / 2)
            return
        }

        // ── 왼쪽: 현재 선택된 곡 정보 ─────────────────────────────────────────
        curSong?.let { entry ->
            val imgSize = minOf(COVER_MAX, LEFT_W - PAD * 2)
            val imgX    = (LEFT_W - imgSize) / 2
            val imgY    = 56

            val cover = getCoverImage(entry)
            if (cover != null) {
                // 부드러운 모서리 클립
                val arc  = 10
                val old  = g.clip
                val rr   = java.awt.geom.RoundRectangle2D.Float(imgX.toFloat(), imgY.toFloat(),
                    imgSize.toFloat(), imgSize.toFloat(), arc.toFloat(), arc.toFloat())
                g.clip = rr
                g.drawImage(cover, imgX, imgY, imgSize, imgSize, null)
                g.clip = old
            } else {
                g.color = Color(38, 28, 60)
                g.fillRoundRect(imgX, imgY, imgSize, imgSize, 10, 10)
                g.color = Color(80, 62, 110)
                val note = "♪"
                val nfm  = g.getFontMetrics(titleLFont)
                g.font   = titleLFont
                g.drawString(note, imgX + (imgSize - nfm.stringWidth(note)) / 2,
                    imgY + imgSize / 2 + nfm.ascent / 2 - 4)
            }

            var ty = imgY + imgSize + 22

            g.font  = titleLFont
            g.color = Color.WHITE
            val titleStr = entry.song.title
            val tfm      = g.getFontMetrics(titleLFont)
            // 긴 제목은 줄바꿈 없이 왼쪽 패딩 기준으로 클립
            g.drawString(titleStr, PAD, ty); ty += tfm.height + 2

            g.font  = artistLFont
            g.color = Color(160, 140, 200)
            g.drawString(entry.song.artist, PAD, ty); ty += 26

            // BPM
            entry.song.bpm?.let {
                g.font  = bpmFont
                g.color = Color(90, 78, 120)
                g.drawString("BPM  $it", PAD, ty); ty += 22
            }

            // 난이도 선택기
            if (curDiffs.isNotEmpty()) {
                ty += 8
                val diff = curDiffs.getOrNull(diffIndex) ?: ""
                g.font  = diffFont
                g.color = Color(220, 185, 80)
                g.drawString("◀  $diff  ▶", PAD, ty)
            }
        }

        // ── 오른쪽: 스크롤 목록 ───────────────────────────────────────────────
        val listX    = LEFT_W + 1
        val listW    = w - listX
        val topPad   = 48
        val areaTop  = topPad
        val areaH    = h - areaTop - 28

        val oldClip = g.clip
        g.setClip(listX, areaTop, listW, areaH)

        // 선택된 행이 항상 위쪽 1/3 지점에 오도록 오프셋
        val anchorY  = areaTop + areaH / 3 - ROW_H / 2
        val selOffY  = anchorY - songIndex * ROW_H

        for (i in songs.indices) {
            val rowY = selOffY + i * ROW_H
            if (rowY + ROW_H < areaTop || rowY > areaTop + areaH) continue

            val entry    = songs[i]
            val selected = i == songIndex

            // 선택 행 배경 (얇은 하이라이트, 선 없음)
            if (selected) {
                g.color = Color(75, 55, 125, 200)
                g.fillRect(listX, rowY, listW, ROW_H)
                // 좌측 액센트 바 (2px)
                g.color = Color(190, 140, 255)
                g.fillRect(listX, rowY, 2, ROW_H)
            } else if (i % 2 == 0) {
                g.color = Color(255, 255, 255, 5)
                g.fillRect(listX, rowY, listW, ROW_H)
            }

            // 구분선 (선택 행 제외, 매우 얇게)
            if (!selected) {
                g.color = Color(50, 42, 72)
                g.drawLine(listX + THUMB_S + 16, rowY + ROW_H - 1, listX + listW - PAD, rowY + ROW_H - 1)
            }

            // 썸네일
            val thumbX = listX + 16
            val thumbY = rowY + (ROW_H - THUMB_S) / 2
            val thumb  = getCoverImage(entry)
            if (thumb != null) {
                val oldC = g.clip
                val rr2  = java.awt.geom.RoundRectangle2D.Float(thumbX.toFloat(), thumbY.toFloat(),
                    THUMB_S.toFloat(), THUMB_S.toFloat(), 6f, 6f)
                g.clip = rr2
                g.drawImage(thumb, thumbX, thumbY, THUMB_S, THUMB_S, null)
                g.clip = oldC
            } else {
                g.color = Color(38, 28, 60)
                g.fillRoundRect(thumbX, thumbY, THUMB_S, THUMB_S, 6, 6)
            }

            // 텍스트
            val textX = thumbX + THUMB_S + 14
            val tfm   = g.getFontMetrics(titleRFont)
            val titleY = rowY + (ROW_H - tfm.height - 16) / 2 + tfm.ascent
            g.font  = titleRFont
            g.color = if (selected) Color.WHITE else Color(180, 170, 200)
            g.drawString(entry.song.title, textX, titleY)

            g.font  = artistRFont
            g.color = if (selected) Color(200, 175, 240) else Color(100, 90, 130)
            g.drawString(entry.song.artist, textX, titleY + tfm.height + 2)
        }

        g.clip = oldClip
    }

    override fun keyPressed(key: Int, mods: Int) {
        when (key) {
            Keys.UP     -> if (songs.isNotEmpty()) { songIndex = (songIndex - 1 + songs.size) % songs.size; diffIndex = 0; lastPreviewSong = null; playPreviewForCurrent() }
            Keys.DOWN   -> if (songs.isNotEmpty()) { songIndex = (songIndex + 1) % songs.size; diffIndex = 0; lastPreviewSong = null; playPreviewForCurrent() }
            Keys.LEFT   -> if (curDiffs.isNotEmpty()) diffIndex = (diffIndex - 1 + curDiffs.size) % curDiffs.size
            Keys.RIGHT  -> if (curDiffs.isNotEmpty()) diffIndex = (diffIndex + 1) % curDiffs.size
            Keys.ENTER  -> onConfirm()
            Keys.I      -> SwingUtilities.invokeLater { SongImportDialog(ctx.songManager).show() }
            Keys.N      -> if (mode == SelectMode.EDIT) SwingUtilities.invokeLater { NewSongDialog(ctx.songManager).show() }
            Keys.E      -> if (mode == SelectMode.EDIT) SwingUtilities.invokeLater { exportCurrentSong() }
            Keys.ESCAPE -> ctx.stateManager.changeState(MainMenuState(ctx))
        }
    }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        val mx      = x.toInt()
        val my      = y.toInt()
        val h       = 720
        val listX   = LEFT_W + 1
        val areaTop = 48
        val areaH   = h - areaTop - 28
        val anchorY = areaTop + areaH / 3 - ROW_H / 2
        val selOffY = anchorY - songIndex * ROW_H

        if (mx < LEFT_W) return  // 왼쪽 패널 클릭 무시

        for (i in songs.indices) {
            val rowY = selOffY + i * ROW_H
            if (mx in listX..(listX + 1280 - listX) && my in rowY..(rowY + ROW_H)) {
                if (i == songIndex) onConfirm() else { songIndex = i; diffIndex = 0 }
                return
            }
        }
    }

    private fun onConfirm() {
        val entry      = curSong ?: run { log.warn("onConfirm: curSong null"); return }
        val diffName   = curDiffs.getOrNull(diffIndex) ?: run { log.warn("onConfirm: diff null"); return }
        val chartFile  = File(entry.songDir, entry.song.difficulties[diffName] ?: run { log.warn("onConfirm: chartPath null"); return })
        if (!chartFile.exists()) { log.warn("onConfirm: chartFile not found: {}", chartFile); return }
        val chart = runCatching { ChartParser.parseChart(chartFile) }
            .onFailure { log.error("onConfirm: 채보 파싱 실패", it) }
            .getOrNull() ?: return

        log.info("onConfirm: {} / {} → mode={}", entry.song.title, diffName, mode)
        when (mode) {
            SelectMode.PLAY -> ctx.stateManager.changeState(PlayState(ctx, entry, chart))
            SelectMode.EDIT -> ctx.stateManager.changeState(EditorState(ctx, entry, chartFile, chart))
        }
    }

    private fun exportCurrentSong() {
        val entry = curSong ?: return
        val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val fc = javax.swing.JFileChooser().apply {
            dialogTitle   = "곡 내보내기 위치 선택"
            selectedFile  = java.io.File("${entry.song.title}.zip")
            fileFilter    = javax.swing.filechooser.FileNameExtensionFilter("ZIP 파일 (*.zip)", "zip")
        }
        if (fc.showSaveDialog(owner) != javax.swing.JFileChooser.APPROVE_OPTION) return
        var dest = fc.selectedFile
        if (!dest.name.endsWith(".zip", ignoreCase = true)) dest = java.io.File(dest.parentFile, dest.name + ".zip")
        runCatching { SongZipUtil.export(entry, dest) }
            .onSuccess {
                javax.swing.JOptionPane.showMessageDialog(owner,
                    "'${entry.song.title}' 를 ZIP 으로 내보냈습니다.\n${dest.absolutePath}",
                    "내보내기 완료", javax.swing.JOptionPane.INFORMATION_MESSAGE)
            }
            .onFailure { ex ->
                javax.swing.JOptionPane.showMessageDialog(owner,
                    "내보내기 실패: ${ex.message}",
                    "오류", javax.swing.JOptionPane.ERROR_MESSAGE)
            }
    }
}

