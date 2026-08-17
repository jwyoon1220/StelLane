package io.github.jwyoon1220.storyeditor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.jwyoon1220.core.story.ChapterReward
import io.github.jwyoon1220.core.story.ChapterSong
import io.github.jwyoon1220.core.story.DialogueLine
import io.github.jwyoon1220.core.story.StoryChapter
import io.github.jwyoon1220.core.story.StoryPack
import io.github.jwyoon1220.core.story.StoryScene
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultCellEditor
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JToolBar
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.AbstractTableModel

/**
 * StelLane 스토리 모드 데이터(스토리 팩/챕터/컷신/대사) 편집용 독립 Swing 도구.
 * Ren'Py처럼 배경(이미지 또는 영상) + 캐릭터 초상화 + 대사를 챕터 단위로 편집하고,
 * `core/story/StoryManager`가 읽는 것과 동일한 레이아웃으로 저장합니다:
 * `<storyRoot>/<packId>/{pack.json, chapters/<chapterId>.json, images/, videos/}`.
 * 팩(주제별 스토리 묶음) 하나가 폴더 하나이므로, 유저는 루트 아래에 새 폴더를 만드는 것만으로
 * (또는 이 도구의 "새 스토리 팩" 버튼으로) 서로 독립된 여러 주제의 스토리를 만들 수 있습니다.
 * `./gradlew :story-editor:run`으로 독립 실행하거나, 게임 메인 메뉴의 "Story Editor" 항목으로
 * 같은 프로세스 안에서 열 수 있습니다([StoryEditorFrame] 참고).
 */
fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    SwingUtilities.invokeLater {
        StoryEditorFrame().apply {
            // 독립 실행(:story-editor:run) 시에만 창을 닫으면 JVM을 종료합니다 — 게임 안에서 열렸을 때는
            // (StoryEditorFrame이 DISPOSE_ON_CLOSE라) 창만 닫히고 게임 프로세스는 계속 돌아갑니다.
            addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosed(e: java.awt.event.WindowEvent) = kotlin.system.exitProcess(0)
            })
            isVisible = true
        }
    }
}

private val mapper = jacksonObjectMapper()

private fun splitCsv(s: String): List<String> = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** 컷신 하나의 편집 작업본. */
private class CutsceneDraft(
    var id: String = "",
    var timing: String = "",
    var backgroundImage: String? = null,
    var backgroundVideo: String? = null,
    val dialogues: MutableList<DialogueLine> = mutableListOf()
) {
    fun toScene(): StoryScene = StoryScene(
        id = id.trim(),
        timing = timing.trim(),
        backgroundImage = backgroundImage,
        backgroundVideo = backgroundVideo,
        dialogues = dialogues.toList()
    )

    companion object {
        fun from(scene: StoryScene) = CutsceneDraft(
            id = scene.id,
            timing = scene.timing,
            backgroundImage = scene.backgroundImage,
            backgroundVideo = scene.backgroundVideo,
            dialogues = scene.dialogues.toMutableList()
        )
    }
}

/** 챕터 하나의 편집 작업본 — 저장 시 [toChapter]로 불변 [StoryChapter]를 만듭니다. */
private class ChapterDraft(
    var id: String,
    var title: String = "",
    var description: String = "",
    var order: Int = 0,
    var unlockNextChapter: Boolean = true,
    var unlockedCharacters: String = "",
    var unlockedEmojis: String = "",
    val cutscenes: MutableList<CutsceneDraft> = mutableListOf(),
    val requiredSongs: MutableList<ChapterSong> = mutableListOf(),
    var sourceFile: File? = null
) {
    fun toChapter(): StoryChapter = StoryChapter(
        id = id.trim(),
        title = title,
        description = description,
        order = order,
        cutscenes = cutscenes.map { it.toScene() },
        requiredSongs = requiredSongs.toList(),
        rewards = ChapterReward(
            unlockNextChapter = unlockNextChapter,
            unlockedCharacters = splitCsv(unlockedCharacters),
            unlockedEmojis = splitCsv(unlockedEmojis)
        )
    )

    companion object {
        fun from(chapter: StoryChapter, file: File?) = ChapterDraft(
            id = chapter.id,
            title = chapter.title,
            description = chapter.description,
            order = chapter.order,
            unlockNextChapter = chapter.rewards?.unlockNextChapter ?: true,
            unlockedCharacters = (chapter.rewards?.unlockedCharacters ?: emptyList()).joinToString(", "),
            unlockedEmojis = (chapter.rewards?.unlockedEmojis ?: emptyList()).joinToString(", "),
            cutscenes = chapter.cutscenes.map { CutsceneDraft.from(it) }.toMutableList(),
            requiredSongs = chapter.requiredSongs.toMutableList(),
            sourceFile = file
        )
    }
}

/** 스토리 팩(주제별 스토리 묶음) 하나의 편집 작업본 — [dir]이 `<storyRoot>/<packId>/` 폴더입니다. */
private class StoryPackDraft(
    var id: String,
    var title: String = "",
    var description: String = "",
    var order: Int = 0,
    val dir: File
) {
    val chaptersDir: File get() = File(dir, "chapters")
    val imagesDir: File get() = File(dir, "images")
    val videosDir: File get() = File(dir, "videos")

    fun toPack(): StoryPack = StoryPack(id = id.trim(), title = title, description = description, order = order)

    val comboLabel: String get() = "[$order] $id — ${title.ifBlank { "(제목 없음)" }}"

    companion object {
        fun from(pack: StoryPack, dir: File) = StoryPackDraft(
            id = pack.id.ifBlank { dir.name },
            title = pack.title,
            description = pack.description,
            order = pack.order,
            dir = dir
        )
    }
}

/** 대사(dialogues) 테이블 모델 — [rows]를 직접 들고 있는 [CutsceneDraft.dialogues] 리스트를 그대로 편집합니다. */
private class DialogueTableModel : AbstractTableModel() {
    var rows: MutableList<DialogueLine> = mutableListOf()
        private set
    private val columns = arrayOf("화자", "초상화 파일", "위치", "감정", "대사 (더블클릭: 여러 줄 편집)")

    fun setRows(r: MutableList<DialogueLine>) {
        rows = r
        fireTableDataChanged()
    }

    override fun getRowCount() = rows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(col: Int) = columns[col]
    override fun isCellEditable(row: Int, col: Int) = true

    override fun getValueAt(row: Int, col: Int): Any = when (col) {
        0 -> rows[row].character
        1 -> rows[row].characterImage ?: ""
        2 -> rows[row].position
        3 -> rows[row].emotion ?: ""
        4 -> rows[row].text
        else -> ""
    }

    override fun setValueAt(value: Any?, row: Int, col: Int) {
        val line = rows[row]
        val s = value?.toString() ?: ""
        rows[row] = when (col) {
            0 -> line.copy(character = s)
            1 -> line.copy(characterImage = s.ifBlank { null })
            2 -> line.copy(position = s.ifBlank { "center" })
            3 -> line.copy(emotion = s.ifBlank { null })
            4 -> line.copy(text = s)
            else -> line
        }
        fireTableCellUpdated(row, col)
    }

    fun addRow() {
        rows.add(DialogueLine(character = "narrator", position = "center", text = ""))
        fireTableRowsInserted(rows.size - 1, rows.size - 1)
    }

    fun removeRow(index: Int) {
        if (index !in rows.indices) return
        rows.removeAt(index)
        fireTableRowsDeleted(index, index)
    }

    fun moveRow(from: Int, delta: Int): Int {
        val to = from + delta
        if (from !in rows.indices || to !in rows.indices) return from
        val tmp = rows[from]; rows[from] = rows[to]; rows[to] = tmp
        fireTableRowsUpdated(minOf(from, to), maxOf(from, to))
        return to
    }
}

/** 필요 곡(requiredSongs) 테이블 모델. */
private class SongTableModel : AbstractTableModel() {
    var rows: MutableList<ChapterSong> = mutableListOf()
        private set
    private val columns = arrayOf("곡 ID (songEntryId)", "난이도", "최소 정확도(%)", "설명")

    fun setRows(r: MutableList<ChapterSong>) {
        rows = r
        fireTableDataChanged()
    }

    override fun getRowCount() = rows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(col: Int) = columns[col]
    override fun isCellEditable(row: Int, col: Int) = true
    override fun getColumnClass(col: Int): Class<*> = if (col == 2) Integer::class.java else String::class.java

    override fun getValueAt(row: Int, col: Int): Any = when (col) {
        0 -> rows[row].songEntryId
        1 -> rows[row].difficulty
        2 -> rows[row].minAccuracy
        3 -> rows[row].description
        else -> ""
    }

    override fun setValueAt(value: Any?, row: Int, col: Int) {
        val s = rows[row]
        rows[row] = when (col) {
            0 -> s.copy(songEntryId = value?.toString() ?: "")
            1 -> s.copy(difficulty = value?.toString() ?: "Normal")
            2 -> s.copy(minAccuracy = (value as? Int) ?: value?.toString()?.toIntOrNull() ?: 0)
            3 -> s.copy(description = value?.toString() ?: "")
            else -> s
        }
        fireTableCellUpdated(row, col)
    }

    fun addRow() {
        rows.add(ChapterSong())
        fireTableRowsInserted(rows.size - 1, rows.size - 1)
    }

    fun removeRow(index: Int) {
        if (index !in rows.indices) return
        rows.removeAt(index)
        fireTableRowsDeleted(index, index)
    }

    fun moveRow(from: Int, delta: Int): Int {
        val to = from + delta
        if (from !in rows.indices || to !in rows.indices) return from
        val tmp = rows[from]; rows[from] = rows[to]; rows[to] = tmp
        fireTableRowsUpdated(minOf(from, to), maxOf(from, to))
        return to
    }
}

private fun labeledRow(label: String, field: java.awt.Component): JPanel =
    JPanel(BorderLayout(8, 0)).apply {
        add(JLabel(label).apply { preferredSize = Dimension(130, preferredSize.height) }, BorderLayout.WEST)
        add(field, BorderLayout.CENTER)
        border = BorderFactory.createEmptyBorder(3, 6, 3, 6)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

/**
 * @param initialStoryRoot 스토리 팩들의 루트 폴더 (`<workingDir>/story/`) — 게임에서 열 때는
 * `GameContext.storyManager.storyDir`를 그대로 넘깁니다. 기본값은 [defaultStoryRootDir]로, 독립 실행 시
 * 이 프로젝트의 기본 스토리 리소스 폴더를 자동으로 찾습니다.
 */
class StoryEditorFrame(initialStoryRoot: File = defaultStoryRootDir()) : JFrame("StelLane Story Editor") {

    private var storyRoot: File = initialStoryRoot

    private val packs = mutableListOf<StoryPackDraft>()
    private var currentPackIndex = -1
    private val currentPack: StoryPackDraft? get() = packs.getOrNull(currentPackIndex)
    private var suppressPackComboEvents = false

    private val chapters = mutableListOf<ChapterDraft>()
    private var currentChapterIndex = -1
    private var currentCutsceneIndex = -1

    // ── 상단 툴바 ──────────────────────────────────────────────────────────
    private val dirField = JTextField(storyRoot.absolutePath).apply { isEditable = false }
    private val statusLabel = JLabel(" ").apply { border = BorderFactory.createEmptyBorder(4, 8, 4, 8) }
    private val packCombo = JComboBox<String>()

    // ── 챕터 목록 ──────────────────────────────────────────────────────────
    private val chapterListModel = DefaultListModel<String>()
    private val chapterList = JList(chapterListModel)

    // ── 챕터 메타 폼 ───────────────────────────────────────────────────────
    private val idField = JTextField()
    private val titleField = JTextField()
    private val descArea = JTextArea(4, 20).apply { lineWrap = true; wrapStyleWord = true }
    private val orderSpinner = JSpinner(SpinnerNumberModel(1, 0, 999, 1))
    private val unlockNextCheck = JCheckBox("다음 챕터 잠금 해제", true)
    private val unlockedCharsField = JTextField()
    private val unlockedEmojisField = JTextField()

    // ── 컷신 목록/폼 ───────────────────────────────────────────────────────
    private val cutsceneListModel = DefaultListModel<String>()
    private val cutsceneList = JList(cutsceneListModel)
    private val cutsceneIdField = JTextField()
    private val timingField = JTextField()
    private val bgImageLabel = JLabel("(없음)")
    private val bgVideoLabel = JLabel("(없음)")

    // ── 대사 테이블 ────────────────────────────────────────────────────────
    private val dialogueTableModel = DialogueTableModel()
    private val dialogueTable = JTable(dialogueTableModel)

    // ── 필요 곡 테이블 ─────────────────────────────────────────────────────
    private val songTableModel = SongTableModel()
    private val songTable = JTable(songTableModel)

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        layout = BorderLayout()
        add(buildToolbar(), BorderLayout.NORTH)
        add(buildMainSplit(), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        preferredSize = Dimension(1320, 840)
        pack()
        setLocationRelativeTo(null)

        wireDialogueTable()
        wireSongTable()
        scanRoot()
    }

    // ── 상단 툴바 ──────────────────────────────────────────────────────────

    private fun buildToolbar(): JToolBar = JToolBar().apply {
        isFloatable = false
        add(JButton("루트 폴더 열기...").apply { addActionListener { chooseRootFolder() } })
        add(JButton("새로고침").apply { addActionListener { scanRoot() } })
        addSeparator()
        add(JLabel(" 팩: "))
        add(packCombo.apply {
            preferredSize = Dimension(220, preferredSize.height)
            addActionListener {
                if (suppressPackComboEvents) return@addActionListener
                val idx = selectedIndex
                if (idx >= 0 && idx != currentPackIndex) selectPack(idx)
            }
        })
        add(JButton("새 스토리 팩").apply { addActionListener { createPack() } })
        add(JButton("팩 정보 수정").apply { addActionListener { editPackInfo() } })
        add(JButton("팩 삭제").apply { addActionListener { deleteCurrentPack() } })
        addSeparator()
        add(JButton("새 챕터").apply { addActionListener { createChapter() } })
        add(JButton("챕터 삭제").apply { addActionListener { deleteCurrentChapter() } })
        addSeparator()
        add(JButton("저장").apply { addActionListener { saveCurrentChapter() } })
        add(JButton("모두 저장").apply { addActionListener { saveAllChapters() } })
        addSeparator()
        add(JLabel(" 루트: "))
        add(dirField.apply { preferredSize = Dimension(360, preferredSize.height) })
    }

    private fun chooseRootFolder() {
        val chooser = JFileChooser(storyRoot).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        storyRoot = chooser.selectedFile
        dirField.text = storyRoot.absolutePath
        scanRoot()
    }

    // ── 메인 레이아웃 ──────────────────────────────────────────────────────

    private fun buildMainSplit(): JSplitPane {
        val chapterPanel = JPanel(BorderLayout(4, 4)).apply {
            border = BorderFactory.createTitledBorder("챕터")
            add(JScrollPane(chapterList), BorderLayout.CENTER)
            preferredSize = Dimension(220, 0)
        }
        chapterList.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            syncChapterFormToDraft()
            currentChapterIndex = chapterList.selectedIndex
            if (currentChapterIndex >= 0) loadChapterIntoForm(currentChapterIndex) else clearChapterForm()
        }

        val tabs = JTabbedPane()
        tabs.addTab("챕터 정보", buildChapterInfoTab())
        tabs.addTab("컷신 & 대사", buildCutsceneTab())
        tabs.addTab("필요 곡", buildSongsTab())

        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chapterPanel, tabs).apply {
            dividerLocation = 220
            resizeWeight = 0.0
        }
    }

    // ── 탭 1: 챕터 정보 ────────────────────────────────────────────────────

    private fun buildChapterInfoTab(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        add(labeledRow("챕터 ID", idField))
        add(labeledRow("제목", titleField))
        add(labeledRow("순서(order)", orderSpinner.apply { maximumSize = Dimension(80, preferredSize.height) }))
        add(JLabel("설명").apply { border = BorderFactory.createEmptyBorder(6, 6, 2, 6) })
        add(JScrollPane(descArea).apply { maximumSize = Dimension(Int.MAX_VALUE, 100) })
        add(Box.createVerticalStrut(10))
        add(JLabel("보상 (rewards)").apply {
            font = font.deriveFont(java.awt.Font.BOLD)
            border = BorderFactory.createEmptyBorder(6, 6, 2, 6)
        })
        add(labeledRow("", unlockNextCheck))
        add(labeledRow("해금 캐릭터 (쉼표 구분)", unlockedCharsField))
        add(labeledRow("해금 이모지 (쉼표 구분)", unlockedEmojisField))
        add(Box.createVerticalGlue())
    }

    // ── 탭 2: 컷신 & 대사 ──────────────────────────────────────────────────

    private fun buildCutsceneTab(): JComponent {
        val listPanel = JPanel(BorderLayout(4, 4)).apply {
            border = BorderFactory.createTitledBorder("컷신 목록")
            add(JScrollPane(cutsceneList), BorderLayout.CENTER)
            add(JPanel(GridLayout(1, 4, 4, 0)).apply {
                add(JButton("추가").apply { addActionListener { addCutscene() } })
                add(JButton("삭제").apply { addActionListener { removeCutscene() } })
                add(JButton("▲").apply { addActionListener { moveCutscene(-1) } })
                add(JButton("▼").apply { addActionListener { moveCutscene(1) } })
            }, BorderLayout.SOUTH)
            preferredSize = Dimension(260, 0)
        }
        cutsceneList.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            syncCutsceneFormToDraft()
            currentCutsceneIndex = cutsceneList.selectedIndex
            if (currentChapterIndex >= 0 && currentCutsceneIndex >= 0) {
                loadCutsceneIntoForm(currentChapterIndex, currentCutsceneIndex)
            } else {
                clearCutsceneForm()
            }
        }

        val formPanel = JPanel(BorderLayout(4, 8))
        val topForm = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(labeledRow("컷신 ID", cutsceneIdField))
            add(labeledRow("타이밍 (before_song_N / after_song_N)", timingField))
            add(labeledRow("배경 이미지", JPanel(BorderLayout(4, 0)).apply {
                add(bgImageLabel, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                    add(JButton("선택...").apply { addActionListener { pickBackgroundImage() } })
                    add(JButton("지우기").apply { addActionListener { clearBackgroundImage() } })
                }, BorderLayout.EAST)
            }))
            add(labeledRow("배경 영상 (설정 시 이미지보다 우선)", JPanel(BorderLayout(4, 0)).apply {
                add(bgVideoLabel, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                    add(JButton("선택...").apply { addActionListener { pickBackgroundVideo() } })
                    add(JButton("지우기").apply { addActionListener { clearBackgroundVideo() } })
                }, BorderLayout.EAST)
            }))
        }
        formPanel.add(topForm, BorderLayout.NORTH)

        val dialoguePanel = JPanel(BorderLayout(4, 4)).apply {
            border = BorderFactory.createTitledBorder("대사")
            add(JScrollPane(dialogueTable), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                add(JButton("대사 추가").apply { addActionListener { dialogueTableModel.addRow() } })
                add(JButton("대사 삭제").apply {
                    addActionListener {
                        val row = dialogueTable.selectedRow
                        if (row >= 0) dialogueTableModel.removeRow(row)
                    }
                })
                add(JButton("▲").apply {
                    addActionListener {
                        val row = dialogueTable.selectedRow
                        if (row >= 0) {
                            val to = dialogueTableModel.moveRow(row, -1)
                            dialogueTable.setRowSelectionInterval(to, to)
                        }
                    }
                })
                add(JButton("▼").apply {
                    addActionListener {
                        val row = dialogueTable.selectedRow
                        if (row >= 0) {
                            val to = dialogueTableModel.moveRow(row, 1)
                            dialogueTable.setRowSelectionInterval(to, to)
                        }
                    }
                })
                add(JButton("초상화 선택...").apply {
                    addActionListener {
                        val row = dialogueTable.selectedRow
                        if (row < 0) return@addActionListener
                        val name = pickAndCopyImage() ?: return@addActionListener
                        dialogueTableModel.setValueAt(name, row, 1)
                    }
                })
            }, BorderLayout.SOUTH)
        }
        formPanel.add(dialoguePanel, BorderLayout.CENTER)

        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, formPanel).apply {
            dividerLocation = 260
            resizeWeight = 0.0
        }
    }

    private fun wireDialogueTable() {
        dialogueTable.columnModel.getColumn(2).cellEditor = DefaultCellEditor(JComboBox(arrayOf("left", "center", "right")))
        dialogueTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val col = dialogueTable.columnAtPoint(e.point)
                val row = dialogueTable.rowAtPoint(e.point)
                if (col != 4 || row !in dialogueTableModel.rows.indices) return
                val area = JTextArea(dialogueTableModel.rows[row].text, 10, 44).apply { lineWrap = true; wrapStyleWord = true }
                val result = JOptionPane.showConfirmDialog(
                    this@StoryEditorFrame, JScrollPane(area), "대사 편집 (여러 줄 가능)",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
                )
                if (result == JOptionPane.OK_OPTION) dialogueTableModel.setValueAt(area.text, row, 4)
            }
        })
    }

    // ── 탭 3: 필요 곡 ──────────────────────────────────────────────────────

    private fun buildSongsTab(): JPanel = JPanel(BorderLayout(4, 4)).apply {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        add(JScrollPane(songTable), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(JButton("추가").apply { addActionListener { songTableModel.addRow() } })
            add(JButton("삭제").apply {
                addActionListener {
                    val row = songTable.selectedRow
                    if (row >= 0) songTableModel.removeRow(row)
                }
            })
            add(JButton("▲").apply {
                addActionListener {
                    val row = songTable.selectedRow
                    if (row >= 0) songTable.setRowSelectionInterval(songTableModel.moveRow(row, -1), songTableModel.moveRow(row, -1))
                }
            })
            add(JButton("▼").apply {
                addActionListener {
                    val row = songTable.selectedRow
                    if (row >= 0) songTable.setRowSelectionInterval(songTableModel.moveRow(row, 1), songTableModel.moveRow(row, 1))
                }
            })
        }, BorderLayout.SOUTH)
    }

    private fun wireSongTable() {
        // ChapterSong.minAccuracy는 Int 컬럼 — 기본 JTable 정수 렌더러/에디터로 충분합니다.
    }

    // ── 팩 목록 스캔/생성/수정/삭제 ─────────────────────────────────────────

    private fun scanRoot() {
        packs.clear()
        val dirs = (storyRoot.listFiles { f -> f.isDirectory } ?: emptyArray()).sortedBy { it.name }
        for (d in dirs) {
            val metaFile = File(d, "pack.json")
            if (!metaFile.isFile) continue
            val meta = runCatching { mapper.readValue(metaFile, StoryPack::class.java) }.getOrNull() ?: continue
            packs.add(StoryPackDraft.from(meta, d))
        }
        packs.sortBy { it.order }
        currentPackIndex = -1
        refreshPackCombo()
        if (packs.isNotEmpty()) {
            selectPack(0)
        } else {
            chapters.clear()
            refreshChapterListLabels()
            clearChapterForm()
            clearCutsceneForm()
            statusLabel.text = "스토리 팩이 없습니다 — '새 스토리 팩' 버튼으로 만들어보세요. 루트: $storyRoot"
        }
    }

    private fun refreshPackCombo() {
        suppressPackComboEvents = true
        packCombo.removeAllItems()
        packs.forEach { packCombo.addItem(it.comboLabel) }
        suppressPackComboEvents = false
    }

    private fun selectPack(index: Int) {
        if (index !in packs.indices) return
        syncChapterFormToDraft()
        syncCutsceneFormToDraft()
        currentPackIndex = index
        suppressPackComboEvents = true
        packCombo.selectedIndex = index
        suppressPackComboEvents = false
        loadChaptersForCurrentPack()
    }

    private fun createPack() {
        val id = JOptionPane.showInputDialog(this, "새 스토리 팩 ID (폴더명이 됩니다, 예: my_new_story)")?.trim()
        if (id.isNullOrBlank()) return
        if (id.contains("/") || id.contains("\\")) {
            JOptionPane.showMessageDialog(this, "ID에는 경로 구분자를 사용할 수 없습니다.")
            return
        }
        if (packs.any { it.id == id } || File(storyRoot, id).exists()) {
            JOptionPane.showMessageDialog(this, "이미 같은 이름의 팩이 있습니다.")
            return
        }
        val title = (JOptionPane.showInputDialog(this, "팩 제목", id) ?: id).trim()
        val dir = File(storyRoot, id)
        File(dir, "chapters").mkdirs()
        File(dir, "images").mkdirs()
        File(dir, "videos").mkdirs()
        val nextOrder = (packs.maxOfOrNull { it.order } ?: 0) + 1
        val pack = StoryPack(id = id, title = title.ifBlank { id }, description = "", order = nextOrder)
        mapper.writerWithDefaultPrettyPrinter().writeValue(File(dir, "pack.json"), pack)
        scanRoot()
        val idx = packs.indexOfFirst { it.id == id }
        if (idx >= 0) selectPack(idx)
        statusLabel.text = "새 팩 생성됨: $id"
    }

    private fun editPackInfo() {
        val pack = currentPack ?: run {
            statusLabel.text = "먼저 팩을 선택하세요."
            return
        }
        val titleField2 = JTextField(pack.title)
        val descArea2 = JTextArea(pack.description, 4, 30).apply { lineWrap = true; wrapStyleWord = true }
        val orderSpinner2 = JSpinner(SpinnerNumberModel(pack.order, 0, 999, 1))
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(labeledRow("제목", titleField2))
            add(labeledRow("순서(order)", orderSpinner2.apply { maximumSize = Dimension(80, preferredSize.height) }))
            add(JLabel("설명").apply { border = BorderFactory.createEmptyBorder(6, 6, 2, 6) })
            add(JScrollPane(descArea2).apply { maximumSize = Dimension(Int.MAX_VALUE, 100) })
        }
        val result = JOptionPane.showConfirmDialog(
            this, panel, "팩 정보 수정 — ${pack.id}", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result != JOptionPane.OK_OPTION) return
        pack.title = titleField2.text
        pack.description = descArea2.text
        pack.order = orderSpinner2.value as Int
        mapper.writerWithDefaultPrettyPrinter().writeValue(File(pack.dir, "pack.json"), pack.toPack())
        val packId = pack.id
        scanRoot()
        val newIdx = packs.indexOfFirst { it.id == packId }
        if (newIdx >= 0) selectPack(newIdx)
        statusLabel.text = "팩 정보 저장됨: $packId"
    }

    private fun deleteCurrentPack() {
        val pack = currentPack ?: return
        val confirm = JOptionPane.showConfirmDialog(
            this, "팩 '${pack.id}'을(를) 삭제할까요? (챕터/이미지/영상이 모두 삭제됩니다)",
            "팩 삭제", JOptionPane.YES_NO_OPTION
        )
        if (confirm != JOptionPane.YES_OPTION) return
        pack.dir.deleteRecursively()
        scanRoot()
        statusLabel.text = "삭제됨: ${pack.id}"
    }

    // ── 챕터 목록 스캔/생성/삭제/저장 ──────────────────────────────────────

    private fun loadChaptersForCurrentPack() {
        chapters.clear()
        currentChapterIndex = -1
        currentCutsceneIndex = -1
        val pack = currentPack
        if (pack == null) {
            refreshChapterListLabels()
            clearChapterForm()
            clearCutsceneForm()
            return
        }
        val files = pack.chaptersDir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray()
        var failCount = 0
        for (f in files.sortedBy { it.name }) {
            runCatching { mapper.readValue(f, StoryChapter::class.java) }
                .onSuccess { chapters.add(ChapterDraft.from(it, f)) }
                .onFailure { failCount++ }
        }
        chapters.sortBy { it.order }
        refreshChapterListLabels()
        clearChapterForm()
        clearCutsceneForm()
        if (chapters.isNotEmpty()) chapterList.selectedIndex = 0
        statusLabel.text = if (failCount > 0) {
            "[${pack.id}] ${chapters.size}개 챕터 로드됨 (파싱 실패 ${failCount}개는 건너뜀)"
        } else {
            "[${pack.id}] ${chapters.size}개 챕터 로드됨 — ${pack.chaptersDir}"
        }
    }

    private fun createChapter() {
        val pack = currentPack
        if (pack == null) {
            statusLabel.text = "먼저 팩을 선택하거나 새로 만드세요."
            return
        }
        val id = JOptionPane.showInputDialog(this, "새 챕터 ID (파일명이 됩니다, 예: ch7_new_horizon)")?.trim()
        if (id.isNullOrBlank()) return
        if (id.contains("/") || id.contains("\\")) {
            JOptionPane.showMessageDialog(this, "ID에는 경로 구분자를 사용할 수 없습니다.")
            return
        }
        if (chapters.any { it.id == id }) {
            JOptionPane.showMessageDialog(this, "이미 같은 ID의 챕터가 있습니다.")
            return
        }
        syncChapterFormToDraft()
        val nextOrder = (chapters.maxOfOrNull { it.order } ?: 0) + 1
        chapters.add(ChapterDraft(id = id, title = id, order = nextOrder))
        refreshChapterListLabels()
        chapterList.selectedIndex = chapters.size - 1
    }

    private fun deleteCurrentChapter() {
        val idx = currentChapterIndex
        if (idx < 0) return
        val d = chapters[idx]
        val confirm = JOptionPane.showConfirmDialog(
            this, "챕터 '${d.id}'를 삭제할까요? (저장된 파일도 함께 삭제됩니다)",
            "챕터 삭제", JOptionPane.YES_NO_OPTION
        )
        if (confirm != JOptionPane.YES_OPTION) return
        d.sourceFile?.let { if (it.exists()) it.delete() }
        chapters.removeAt(idx)
        currentChapterIndex = -1
        currentCutsceneIndex = -1
        refreshChapterListLabels()
        clearChapterForm()
        clearCutsceneForm()
        if (chapters.isNotEmpty()) chapterList.selectedIndex = minOf(idx, chapters.size - 1)
        statusLabel.text = "삭제됨: ${d.id}"
    }

    private fun saveCurrentChapter() {
        if (currentChapterIndex < 0) {
            statusLabel.text = "저장할 챕터가 선택되지 않았습니다."
            return
        }
        syncChapterFormToDraft()
        syncCutsceneFormToDraft()
        saveChapter(chapters[currentChapterIndex])
    }

    private fun saveAllChapters() {
        syncChapterFormToDraft()
        syncCutsceneFormToDraft()
        var count = 0
        for (d in chapters) {
            if (saveChapter(d, silent = true)) count++
        }
        refreshChapterListLabels()
        statusLabel.text = "${count}개 챕터 저장됨 — ${currentPack?.chaptersDir}"
    }

    private fun saveChapter(d: ChapterDraft, silent: Boolean = false): Boolean {
        val pack = currentPack
        if (pack == null) {
            statusLabel.text = "저장 실패: 선택된 팩이 없습니다"
            return false
        }
        if (d.id.isBlank()) {
            statusLabel.text = "저장 실패: id가 비어있습니다"
            return false
        }
        pack.chaptersDir.mkdirs()
        val chapter = d.toChapter()
        val file = File(pack.chaptersDir, "${chapter.id}.json")
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, chapter)
        val renamed = d.sourceFile != null && d.sourceFile!!.name != file.name
        d.sourceFile = file
        if (!silent) {
            statusLabel.text = if (renamed) {
                "저장됨: ${file.name} (ID가 바뀌어 이전 파일이 남아있을 수 있습니다 — 필요하면 직접 삭제하세요)"
            } else {
                "저장됨: ${file.name}"
            }
            refreshChapterListLabels()
        }
        return true
    }

    private fun refreshChapterListLabels() {
        val selected = chapterList.selectedIndex
        chapterListModel.clear()
        chapters.forEach { d -> chapterListModel.addElement("[${d.order}] ${d.id}") }
        if (selected in chapters.indices) chapterList.selectedIndex = selected
    }

    // ── 챕터 폼 <-> ChapterDraft 동기화 ────────────────────────────────────

    private fun syncChapterFormToDraft() {
        val idx = currentChapterIndex
        if (idx !in chapters.indices) return
        val d = chapters[idx]
        d.id = idField.text.trim()
        d.title = titleField.text
        d.description = descArea.text
        d.order = orderSpinner.value as Int
        d.unlockNextChapter = unlockNextCheck.isSelected
        d.unlockedCharacters = unlockedCharsField.text
        d.unlockedEmojis = unlockedEmojisField.text
    }

    private fun loadChapterIntoForm(idx: Int) {
        val d = chapters[idx]
        idField.text = d.id
        titleField.text = d.title
        descArea.text = d.description
        orderSpinner.value = d.order
        unlockNextCheck.isSelected = d.unlockNextChapter
        unlockedCharsField.text = d.unlockedCharacters
        unlockedEmojisField.text = d.unlockedEmojis

        cutsceneListModel.clear()
        d.cutscenes.forEach { cs -> cutsceneListModel.addElement(cutsceneLabel(cs)) }
        currentCutsceneIndex = -1
        songTableModel.setRows(d.requiredSongs)
        if (d.cutscenes.isNotEmpty()) cutsceneList.selectedIndex = 0 else clearCutsceneForm()
    }

    private fun clearChapterForm() {
        idField.text = ""
        titleField.text = ""
        descArea.text = ""
        orderSpinner.value = 0
        unlockNextCheck.isSelected = true
        unlockedCharsField.text = ""
        unlockedEmojisField.text = ""
        cutsceneListModel.clear()
        songTableModel.setRows(mutableListOf())
        clearCutsceneForm()
    }

    // ── 컷신 폼 <-> CutsceneDraft 동기화 ───────────────────────────────────

    private fun syncCutsceneFormToDraft() {
        val ci = currentChapterIndex
        val si = currentCutsceneIndex
        if (ci !in chapters.indices) return
        if (si !in chapters[ci].cutscenes.indices) return
        val cs = chapters[ci].cutscenes[si]
        cs.id = cutsceneIdField.text.trim()
        cs.timing = timingField.text.trim()
    }

    private fun loadCutsceneIntoForm(ci: Int, si: Int) {
        val cs = chapters[ci].cutscenes[si]
        cutsceneIdField.text = cs.id
        timingField.text = cs.timing
        bgImageLabel.text = cs.backgroundImage ?: "(없음)"
        bgVideoLabel.text = cs.backgroundVideo ?: "(없음)"
        dialogueTableModel.setRows(cs.dialogues)
    }

    private fun clearCutsceneForm() {
        cutsceneIdField.text = ""
        timingField.text = ""
        bgImageLabel.text = "(없음)"
        bgVideoLabel.text = "(없음)"
        dialogueTableModel.setRows(mutableListOf())
    }

    private fun cutsceneLabel(cs: CutsceneDraft): String =
        "${cs.id.ifBlank { "(id 없음)" }} — ${cs.timing.ifBlank { "timing 없음" }}"

    private fun refreshCutsceneListLabels() {
        val ci = currentChapterIndex
        if (ci !in chapters.indices) return
        val selected = cutsceneList.selectedIndex
        cutsceneListModel.clear()
        chapters[ci].cutscenes.forEach { cs -> cutsceneListModel.addElement(cutsceneLabel(cs)) }
        if (selected in chapters[ci].cutscenes.indices) cutsceneList.selectedIndex = selected
    }

    // ── 컷신 추가/삭제/이동 ────────────────────────────────────────────────

    private fun addCutscene() {
        val ci = currentChapterIndex
        if (ci < 0) {
            statusLabel.text = "먼저 챕터를 선택하세요."
            return
        }
        syncCutsceneFormToDraft()
        val d = chapters[ci]
        d.cutscenes.add(CutsceneDraft(id = "scene_${d.cutscenes.size + 1}", timing = "before_song_0"))
        refreshCutsceneListLabels()
        cutsceneList.selectedIndex = d.cutscenes.size - 1
    }

    private fun removeCutscene() {
        val ci = currentChapterIndex
        val si = currentCutsceneIndex
        if (ci < 0 || si !in chapters[ci].cutscenes.indices) return
        chapters[ci].cutscenes.removeAt(si)
        currentCutsceneIndex = -1
        refreshCutsceneListLabels()
        if (chapters[ci].cutscenes.isNotEmpty()) {
            cutsceneList.selectedIndex = minOf(si, chapters[ci].cutscenes.size - 1)
        } else {
            clearCutsceneForm()
        }
    }

    private fun moveCutscene(delta: Int) {
        val ci = currentChapterIndex
        val si = currentCutsceneIndex
        if (ci < 0 || si !in chapters[ci].cutscenes.indices) return
        syncCutsceneFormToDraft()
        val list = chapters[ci].cutscenes
        val to = si + delta
        if (to !in list.indices) return
        val tmp = list[si]; list[si] = list[to]; list[to] = tmp
        refreshCutsceneListLabels()
        cutsceneList.selectedIndex = to
    }

    // ── 배경 이미지/영상 선택 ──────────────────────────────────────────────

    private fun pickBackgroundImage() {
        val ci = currentChapterIndex
        val si = currentCutsceneIndex
        if (ci < 0 || si !in chapters[ci].cutscenes.indices) return
        val name = pickAndCopyImage() ?: return
        chapters[ci].cutscenes[si].backgroundImage = name
        bgImageLabel.text = name
    }

    private fun clearBackgroundImage() {
        val ci = currentChapterIndex
        val si = currentCutsceneIndex
        if (ci < 0 || si !in chapters[ci].cutscenes.indices) return
        chapters[ci].cutscenes[si].backgroundImage = null
        bgImageLabel.text = "(없음)"
    }

    private fun pickBackgroundVideo() {
        val ci = currentChapterIndex
        val si = currentCutsceneIndex
        if (ci < 0 || si !in chapters[ci].cutscenes.indices) return
        val pack = currentPack ?: return
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("영상 (mp4, webm, mkv, mov)", "mp4", "webm", "mkv", "mov")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val name = copyIntoStoryDir(chooser.selectedFile, pack.videosDir)
        chapters[ci].cutscenes[si].backgroundVideo = name
        bgVideoLabel.text = name
    }

    private fun clearBackgroundVideo() {
        val ci = currentChapterIndex
        val si = currentCutsceneIndex
        if (ci < 0 || si !in chapters[ci].cutscenes.indices) return
        chapters[ci].cutscenes[si].backgroundVideo = null
        bgVideoLabel.text = "(없음)"
    }

    private fun pickAndCopyImage(): String? {
        val pack = currentPack ?: run {
            statusLabel.text = "먼저 팩을 선택하세요."
            return null
        }
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("이미지 (png, jpg, jpeg, gif, webp)", "png", "jpg", "jpeg", "gif", "webp")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return null
        return copyIntoStoryDir(chooser.selectedFile, pack.imagesDir)
    }

    private fun copyIntoStoryDir(src: File, destDir: File): String {
        destDir.mkdirs()
        val dest = File(destDir, src.name)
        if (src.canonicalFile != dest.canonicalFile) {
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return dest.name
    }
}

/** 저장소 안의 기본 스토리 팩 루트 폴더가 있으면 그것을, 없으면 `<실행 위치>/story`를 기본값으로 씁니다. */
private fun defaultStoryRootDir(): File {
    val candidate = File(System.getProperty("user.dir"), "../assets/src/main/resources/story")
    val resolved = runCatching { candidate.canonicalFile }.getOrDefault(candidate)
    return if (resolved.isDirectory) resolved else File(System.getProperty("user.dir"), "story")
}
