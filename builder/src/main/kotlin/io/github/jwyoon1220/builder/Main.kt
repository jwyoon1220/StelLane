package io.github.jwyoon1220.builder

import io.github.jwyoon1220.core.data.Song
import io.github.jwyoon1220.core.song.ChartParser
import java.awt.*
import java.awt.event.ActionEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter

fun main() {
    SwingUtilities.invokeLater { BuilderApp().show() }
}

class BuilderApp {
    // ── 폰트 ─────────────────────────────────────────────────────────────────
    private val fontTitle = Font("Dialog", Font.BOLD, 22)
    private val fontLabel = Font("Dialog", Font.PLAIN, 13)
    private val fontSmall = Font("Dialog", Font.PLAIN, 11)

    // ── 데이터 필드 ───────────────────────────────────────────────────────────
    private val tfTitle    = JTextField(28)
    private val tfArtist   = JTextField(28)
    private val tfBpm      = JTextField(8)
    private val tfDiffs    = JTextField(28).also { it.text = "easy,hard" }

    private val tfAudio    = JTextField(30)
    private val tfVideo    = JTextField(30)
    private val tfCover    = JTextField(30)

    private val tfSongsDir = JTextField(30)

    private val log        = JTextArea(7, 50).also {
        it.isEditable = false; it.font = Font("Monospaced", Font.PLAIN, 11)
    }

    fun show() {
        val frame = JFrame("StelLane  Song Builder").also {
            it.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            it.iconImage = null
        }

        val root = JPanel(BorderLayout(0, 8)).also { it.border = EmptyBorder(16, 20, 16, 20) }

        // 상단 타이틀
        root.add(JLabel("StelLane  Song Builder").also {
            it.font = fontTitle; it.foreground = Color(80, 60, 180)
        }, BorderLayout.NORTH)

        // 메인 폼
        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().also {
            it.insets = Insets(4, 4, 4, 4); it.anchor = GridBagConstraints.WEST
        }
        var row = 0

        fun label(text: String) = JLabel(text).also { it.font = fontLabel }
        fun section(title: String) = JLabel("─── $title ───────────────────").also {
            it.font = fontSmall; it.foreground = Color(120, 100, 200)
        }
        fun addRow(lbl: JComponent, field: JComponent) {
            gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; form.add(lbl, gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; form.add(field, gbc)
            gbc.weightx = 0.0; row++
        }
        fun addSection(title: String) {
            gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL
            form.add(section(title), gbc); gbc.gridwidth = 1; row++
        }
        fun browseButton(target: JTextField, vararg exts: String): JButton {
            return JButton("…").also { btn ->
                btn.addActionListener {
                    val fc = JFileChooser()
                    if (exts.isNotEmpty()) fc.fileFilter = FileNameExtensionFilter("${exts.joinToString(", ")} 파일", *exts)
                    if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION)
                        target.text = fc.selectedFile.absolutePath
                }
            }
        }

        // 메타데이터
        addSection("곡 정보")
        addRow(label("제목 *"), tfTitle)
        addRow(label("아티스트 *"), tfArtist)
        addRow(label("BPM"), tfBpm)
        addRow(label("난이도 (쉼표 구분) *"), tfDiffs)

        // 파일
        addSection("파일 선택")
        val audioPanel = JPanel(BorderLayout(4, 0)).also { it.add(tfAudio); it.add(browseButton(tfAudio, "mp3","ogg","flac","wav","aac"), BorderLayout.EAST) }
        addRow(label("오디오 파일"), audioPanel)
        val videoPanel = JPanel(BorderLayout(4, 0)).also { it.add(tfVideo); it.add(browseButton(tfVideo, "mp4","mkv","avi","mov","webm"), BorderLayout.EAST) }
        addRow(label("영상 파일"), videoPanel)
        val coverPanel = JPanel(BorderLayout(4, 0)).also { it.add(tfCover); it.add(browseButton(tfCover, "jpg","jpeg","png","webp"), BorderLayout.EAST) }
        addRow(label("커버 이미지"), coverPanel)

        // songs 폴더
        addSection("출력 위치")
        val songsDirPanel = JPanel(BorderLayout(4, 0)).also {
            it.add(tfSongsDir)
            it.add(JButton("…").also { btn ->
                btn.addActionListener {
                    val fc = JFileChooser().also { fc -> fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                    if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION)
                        tfSongsDir.text = fc.selectedFile.absolutePath
                }
            }, BorderLayout.EAST)
        }
        addRow(label("songs 폴더 *"), songsDirPanel)

        // 기본값: 실행 위치 하위 songs/
        tfSongsDir.text = File(System.getProperty("user.dir"), "songs").absolutePath

        root.add(form, BorderLayout.CENTER)

        // 하단 버튼 + 로그
        val buildBtn = JButton("  빌드 (Build Song)  ").also {
            it.font = Font("Dialog", Font.BOLD, 14)
            it.background = Color(80, 50, 180); it.foreground = Color.WHITE
            it.isFocusPainted = false; it.cursor = Cursor(Cursor.HAND_CURSOR)
        }
        buildBtn.addActionListener { onBuild(frame) }

        val botPanel = JPanel(BorderLayout(0, 6))
        val btnRow   = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).also { it.add(buildBtn) }
        botPanel.add(btnRow, BorderLayout.NORTH)
        botPanel.add(JScrollPane(log), BorderLayout.CENTER)
        root.add(botPanel, BorderLayout.SOUTH)

        frame.contentPane = root
        frame.pack()
        frame.minimumSize = Dimension(640, 560)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }

    // ── 빌드 로직 ─────────────────────────────────────────────────────────────
    private fun onBuild(parent: JFrame) {
        val title   = tfTitle.text.trim()
        val artist  = tfArtist.text.trim()
        val diffs   = tfDiffs.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val songsDir = File(tfSongsDir.text.trim())

        if (title.isEmpty() || artist.isEmpty() || diffs.isEmpty()) {
            log("❌ 제목, 아티스트, 난이도를 입력하세요.")
            return
        }
        if (!songsDir.exists() && !songsDir.mkdirs()) {
            log("❌ songs 폴더를 만들 수 없습니다: ${songsDir.absolutePath}")
            return
        }

        // slug: 파일명에 쓸 수 없는 문자 제거
        val slug = title.replace(Regex("[^\\w가-힣ㄱ-ㅎㅏ-ㅣ\\-_. ]"), "_").trim()

        val songDir = File(songsDir, slug).also { it.mkdirs() }
        log("📁 곡 폴더: ${songDir.absolutePath}")

        // 파일 복사 헬퍼
        fun copyIfSet(srcPath: String, destName: String?): String? {
            if (srcPath.isBlank()) return null
            val src = File(srcPath)
            if (!src.exists()) { log("⚠ 파일 없음: $srcPath"); return null }
            val dest = File(songDir, destName ?: src.name)
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            log("  복사: ${src.name} → ${dest.name}")
            return dest.name
        }

        val audioName = copyIfSet(tfAudio.text.trim(), null)
        val videoName = copyIfSet(tfVideo.text.trim(), null)
        val coverName = copyIfSet(tfCover.text.trim(), null)

        // 각 난이도별 빈 채보 JSON 생성
        val diffMap = mutableMapOf<String, String>()
        for (diff in diffs) {
            val chartFile = File(songDir, "${diff.lowercase()}.json")
            if (!chartFile.exists()) {
                ChartParser.serializeChart(
                    io.github.jwyoon1220.core.data.Chart(offsetMs = 0L, notes = emptyList()),
                    chartFile
                )
                log("  채보 생성: ${chartFile.name}")
            } else {
                log("  채보 유지 (기존): ${chartFile.name}")
            }
            diffMap[diff] = chartFile.name
        }

        // Song 메타 JSON 저장
        val song = Song(
            title        = title,
            artist       = artist,
            bpm          = tfBpm.text.trim().toIntOrNull(),
            coverImagePath = coverName,
            videoPath    = videoName,
            audioPath    = audioName,
            difficulties = diffMap
        )
        val metaFile = File(songsDir, "$slug.json")
        com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValue(metaFile, song)
        log("✅ 완료: ${metaFile.absolutePath}")
        log("─────────────────────────────────────────")
    }

    private fun log(msg: String) {
        SwingUtilities.invokeLater { log.append("$msg\n"); log.caretPosition = log.document.length }
    }
}
