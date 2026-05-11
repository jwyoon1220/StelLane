package io.github.jwyoon1220.app.ui

import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.Song
import io.github.jwyoon1220.core.song.ChartParser
import io.github.jwyoon1220.core.song.SongManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.*
import javax.swing.table.DefaultTableModel

/**
 * 새 곡 만들기 다이얼로그.
 * Metadata / Media / Difficulties 탭으로 구성되며
 * Create 를 누르면 songs/<folderName>/ + 메타 JSON + 빈 채보 JSON 이 생성된다.
 */
class NewSongDialog(private val songManager: SongManager) {

    fun show() {
        val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val dialog = JDialog(owner, "새 곡 만들기", java.awt.Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.preferredSize = Dimension(640, 500)

        // ── 탭 패널 ──────────────────────────────────────────────────────────
        val tabs = JTabbedPane()

        // ── 탭 1: Metadata ───────────────────────────────────────────────────
        val tfTitle    = JTextField(28)
        val tfArtist   = JTextField(28)
        val tfBpm      = JTextField(8)
        var coverFile: File? = null
        val lblCover   = JLabel("선택 안 됨")

        val metaPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(16, 20, 16, 20)
        }
        fun gbc(row: Int, col: Int, fill: Int = GridBagConstraints.HORIZONTAL, width: Int = 1) =
            GridBagConstraints().also {
                it.gridx = col; it.gridy = row; it.gridwidth = width
                it.fill = fill; it.insets = Insets(4, 4, 4, 4)
            }

        with(metaPanel) {
            add(JLabel("제목 *"), gbc(0, 0, GridBagConstraints.NONE))
            add(tfTitle, gbc(0, 1))
            add(JLabel("아티스트"), gbc(1, 0, GridBagConstraints.NONE))
            add(tfArtist, gbc(1, 1))
            add(JLabel("BPM"), gbc(2, 0, GridBagConstraints.NONE))
            add(tfBpm, gbc(2, 1))
            add(JLabel("커버 이미지"), gbc(3, 0, GridBagConstraints.NONE))
            val coverRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(lblCover)
                add(JButton("파일 선택…").apply {
                    addActionListener {
                        val fc = JFileChooser().apply {
                            dialogTitle = "커버 이미지 선택"
                            fileFilter  = javax.swing.filechooser.FileNameExtensionFilter("이미지", "png", "jpg", "jpeg", "webp")
                        }
                        if (fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                            coverFile = fc.selectedFile
                            lblCover.text = fc.selectedFile.name
                        }
                    }
                })
            }
            add(coverRow, gbc(3, 1))
        }
        tabs.addTab("Metadata", metaPanel)

        // ── 탭 2: Media ──────────────────────────────────────────────────────
        var audioFile: File? = null
        var videoFile: File? = null
        val lblAudio = JLabel("선택 안 됨")
        val lblVideo = JLabel("선택 안 됨")

        val mediaPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(16, 20, 16, 20)
        }
        with(mediaPanel) {
            add(JLabel("오디오 파일"), gbc(0, 0, GridBagConstraints.NONE))
            val audioRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(lblAudio)
                add(JButton("파일 선택…").apply {
                    addActionListener {
                        val fc = JFileChooser().apply {
                            dialogTitle = "오디오 파일 선택"
                            fileFilter  = javax.swing.filechooser.FileNameExtensionFilter("오디오", "mp3", "ogg", "wav", "flac", "m4a")
                        }
                        if (fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                            audioFile = fc.selectedFile
                            lblAudio.text = fc.selectedFile.name
                        }
                    }
                })
            }
            add(audioRow, gbc(0, 1))

            add(JLabel("비디오 파일"), gbc(1, 0, GridBagConstraints.NONE))
            val videoRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(lblVideo)
                add(JButton("파일 선택…").apply {
                    addActionListener {
                        val fc = JFileChooser().apply {
                            dialogTitle = "비디오 파일 선택"
                            fileFilter  = javax.swing.filechooser.FileNameExtensionFilter("비디오", "mp4", "mkv", "avi", "mov", "webm")
                        }
                        if (fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                            videoFile = fc.selectedFile
                            lblVideo.text = fc.selectedFile.name
                        }
                    }
                })
            }
            add(videoRow, gbc(1, 1))

            add(JLabel("<html><small>오디오와 비디오 중 하나는 있어야 채보 에디터에서 재생됩니다.</small></html>"),
                gbc(2, 0, GridBagConstraints.HORIZONTAL, 2))
        }
        tabs.addTab("Media", mediaPanel)

        // ── 탭 3: Difficulties ───────────────────────────────────────────────
        val diffModel = DefaultTableModel(arrayOf("난이도 이름", "채보 파일명 (자동)"), 0)
        val diffTable = JTable(diffModel).apply { rowHeight = 24 }
        // 기본 Easy/Normal/Hard
        listOf("easy", "normal", "hard").forEach { diffModel.addRow(arrayOf(it, "$it.json")) }

        val diffPanel = JPanel(BorderLayout(4, 4)).apply {
            border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
        }
        val scrollDiff = JScrollPane(diffTable)
        val diffBtnPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("추가").apply {
                addActionListener {
                    val name = JOptionPane.showInputDialog(dialog, "난이도 이름 입력:", "난이도 추가", JOptionPane.PLAIN_MESSAGE)
                    if (!name.isNullOrBlank()) {
                        val safe = name.trim()
                        diffModel.addRow(arrayOf(safe, "$safe.json"))
                    }
                }
            })
            add(JButton("삭제").apply {
                addActionListener {
                    val row = diffTable.selectedRow
                    if (row >= 0) diffModel.removeRow(row)
                }
            })
            add(JLabel("<html><small>난이도 이름: easy, normal, hard, expert, … 어떤 이름이든 가능</small></html>"))
        }
        with(diffPanel) {
            add(scrollDiff, BorderLayout.CENTER)
            add(diffBtnPanel, BorderLayout.SOUTH)
        }
        tabs.addTab("Difficulties", diffPanel)

        // ── 하단 버튼 ─────────────────────────────────────────────────────────
        val statusLabel = JLabel(" ").apply { foreground = Color.RED }
        val btnCreate = JButton("Create").apply {
            font = font.deriveFont(Font.BOLD)
            addActionListener {
                val titleText = tfTitle.text.trim()
                if (titleText.isEmpty()) {
                    statusLabel.text = "제목은 필수입니다."; tabs.selectedIndex = 0; return@addActionListener
                }
                if (diffModel.rowCount == 0) {
                    statusLabel.text = "난이도가 최소 1개 필요합니다."; tabs.selectedIndex = 2; return@addActionListener
                }

                val result = runCatching { createSong(titleText, tfArtist.text.trim(), tfBpm.text.trim().toIntOrNull(),
                    coverFile, audioFile, videoFile, diffModel) }
                if (result.isSuccess) {
                    JOptionPane.showMessageDialog(dialog, "'$titleText' 곡이 생성되었습니다!", "완료", JOptionPane.INFORMATION_MESSAGE)
                    dialog.dispose()
                } else {
                    statusLabel.text = "오류: ${result.exceptionOrNull()?.message}"
                }
            }
        }
        val btnCancel = JButton("취소").apply { addActionListener { dialog.dispose() } }

        val bottomPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(4, 16, 8, 16)
            val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(btnCancel); add(btnCreate) }
            add(statusLabel, BorderLayout.WEST)
            add(btnRow, BorderLayout.EAST)
        }

        dialog.contentPane.layout = BorderLayout()
        (dialog.contentPane as java.awt.Container).add(tabs, BorderLayout.CENTER)
        (dialog.contentPane as java.awt.Container).add(bottomPanel, BorderLayout.SOUTH)

        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
    }

    // ── 파일 생성 로직 ────────────────────────────────────────────────────────
    private fun createSong(
        title: String,
        artist: String,
        bpm: Int?,
        coverFile: File?,
        audioFile: File?,
        videoFile: File?,
        diffModel: DefaultTableModel
    ) {
        val songsDir   = File(songManager.workingDir, "songs").also { it.mkdirs() }
        val folderName = sanitizeName(title)
        val songDir    = File(songsDir, folderName).also { it.mkdirs() }
        val metaFile   = File(songsDir, "$folderName.json")

        // 파일 복사
        fun copyIfNeeded(src: File?, destName: String): String? {
            src ?: return null
            val dest = File(songDir, destName)
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return destName
        }

        val coverName = coverFile?.let { copyIfNeeded(it, "cover.${it.extension}") }
        val audioName = audioFile?.let { copyIfNeeded(it, "audio.${it.extension}") }
        val videoName = videoFile?.let { copyIfNeeded(it, "video.${it.extension}") }

        // 난이도 맵 + 빈 채보 생성
        val difficulties = mutableMapOf<String, String>()
        for (row in 0 until diffModel.rowCount) {
            val diffName = diffModel.getValueAt(row, 0).toString().trim()
            val fileName = diffModel.getValueAt(row, 1).toString().trim()
            if (diffName.isBlank()) continue
            difficulties[diffName] = fileName
            val chartFile = File(songDir, fileName)
            if (!chartFile.exists()) {
                ChartParser.serializeChart(Chart(offsetMs = 0L, notes = emptyList()), chartFile)
            }
        }

        // 메타 JSON 작성
        val song = Song(
            title           = title,
            artist          = artist,
            bpm             = bpm,
            coverImagePath  = coverName,
            audioPath       = audioName,
            videoPath       = videoName,
            difficulties    = difficulties
        )
        ChartParser.serializeSong(song, metaFile)

        songManager.refresh()
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9가-힣\\-_ ]"), "_")
            .trim()
            .replace(" ", "_")
            .take(64)
            .ifEmpty { "untitled" }
}
