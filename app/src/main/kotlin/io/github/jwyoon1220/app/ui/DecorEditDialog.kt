package io.github.jwyoon1220.app.ui

import io.github.jwyoon1220.core.data.DecEffect
import io.github.jwyoon1220.core.data.Decoration
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.*

/**
 * 장식 하나(Decoration)의 속성을 편집하는 다이얼로그.
 * show() 이후 showAndGet() 으로 편집된 Decoration 을 반환받는다.
 */
class DecorEditDialog(
    private val initial: Decoration,
    private val songDir: File
) {
    /** 편집 완료 시 갱신된 Decoration, 취소 시 null */
    private var result: Decoration? = null

    fun showAndGet(): Decoration? {
        val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val dialog = JDialog(owner, "장식 편집", java.awt.Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.preferredSize = Dimension(540, 560)

        // ── 탭 ────────────────────────────────────────────────────────────────
        val tabs = JTabbedPane()

        // ── 탭 1: 기본 속성 ──────────────────────────────────────────────────
        val tfId       = JTextField(initial.id, 22)
        val tfImage    = JTextField(initial.image, 22)
        val tfTimeMs   = JTextField("${initial.timeMs}", 12)
        val tfDurMs    = JTextField("${initial.durationMs}", 12)
        val tfX        = JTextField("${initial.x}", 8)
        val tfY        = JTextField("${initial.y}", 8)
        val tfW        = JTextField("${initial.width}", 8)
        val tfH        = JTextField("${initial.height}", 8)
        val tfPivX     = JTextField("${initial.pivotX}", 8)
        val tfPivY     = JTextField("${initial.pivotY}", 8)
        val tfOpacity  = JTextField("${initial.opacity}", 8)
        val tfRotation = JTextField("${initial.rotation}", 8)
        val tfScaleX   = JTextField("${initial.scaleX}", 8)
        val tfScaleY   = JTextField("${initial.scaleY}", 8)
        val tfDepth    = JTextField("${initial.depth}", 8)

        val basicPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
        }
        fun gbc(row: Int, col: Int, fill: Int = GridBagConstraints.HORIZONTAL, width: Int = 1) =
            GridBagConstraints().also {
                it.gridx = col; it.gridy = row; it.gridwidth = width
                it.fill = fill; it.insets = Insets(3, 4, 3, 4)
            }
        fun labelRow(panel: JPanel, row: Int, label: String, field: JTextField) {
            panel.add(JLabel(label), gbc(row, 0, GridBagConstraints.NONE))
            panel.add(field, gbc(row, 1))
        }
        fun twoCol(panel: JPanel, row: Int, label: String, f1: JTextField, label2: String, f2: JTextField) {
            panel.add(JLabel(label), gbc(row, 0, GridBagConstraints.NONE))
            panel.add(f1, gbc(row, 1))
            panel.add(JLabel(label2), gbc(row, 2, GridBagConstraints.NONE))
            panel.add(f2, gbc(row, 3))
        }

        with(basicPanel) {
            labelRow(this, 0, "ID", tfId)
            // 이미지 경로 + 파일 선택 버튼
            add(JLabel("image"), gbc(1, 0, GridBagConstraints.NONE))
            val imgRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(tfImage)
                add(JButton("…").apply {
                    addActionListener {
                        val fc = JFileChooser(songDir).apply {
                            dialogTitle = "이미지 선택"
                            fileFilter  = javax.swing.filechooser.FileNameExtensionFilter("이미지", "png", "jpg", "jpeg", "webp")
                        }
                        if (fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                            tfImage.text = fc.selectedFile.toRelativeString(songDir)
                        }
                    }
                })
            }
            add(imgRow, gbc(1, 1, GridBagConstraints.HORIZONTAL, 3))
            labelRow(this, 2, "timeMs", tfTimeMs)
            labelRow(this, 3, "durationMs", tfDurMs)
            twoCol(this, 4, "x", tfX, "y", tfY)
            twoCol(this, 5, "width", tfW, "height", tfH)
            add(JLabel("<html><small>\u2264 1.0 \u2192 \ud654\uba74 \ube44\uc728 | &gt; 1.0 \u2192 \ub17c\ub9ac\ud53d\uc140</small></html>"),
                gbc(6, 1, GridBagConstraints.HORIZONTAL, 3))
            twoCol(this, 7, "pivotX", tfPivX, "pivotY", tfPivY)
            labelRow(this, 8, "opacity", tfOpacity)
            labelRow(this, 9, "rotation", tfRotation)
            twoCol(this, 10, "scaleX", tfScaleX, "scaleY", tfScaleY)
            labelRow(this, 11, "depth", tfDepth)
        }
        tabs.addTab("기본", basicPanel)

        // ── 탭 2: 이펙트 목록 ────────────────────────────────────────────────
        val effectsModel = object : javax.swing.table.DefaultTableModel(
            arrayOf("type", "startMs", "durationMs", "easing"), 0
        ) {
            override fun isCellEditable(row: Int, col: Int) = false
        }
        initial.effects.forEach { eff ->
            effectsModel.addRow(arrayOf(eff.type, eff.startMs, eff.durationMs, eff.easing))
        }
        // 내부 이펙트 데이터 유지 (table row ↔ DecEffect)
        val effectsList = initial.effects.toMutableList()
        val effectsTable = JTable(effectsModel).apply { rowHeight = 22 }
        val effScroll    = JScrollPane(effectsTable)

        val efBtnPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("추가").apply {
                addActionListener {
                    val typeOptions = arrayOf("fadeIn", "fadeOut", "opacityTo", "moveTo",
                        "rotateTo", "scaleTo", "colorFilter", "shake")
                    val type = JOptionPane.showInputDialog(dialog, "이펙트 타입:",
                        "이펙트 추가", JOptionPane.PLAIN_MESSAGE, null, typeOptions, typeOptions[0]) as? String
                        ?: return@addActionListener
                    val startMs = JOptionPane.showInputDialog(dialog, "시작 ms:", "0")?.toLongOrNull() ?: 0L
                    val durMs   = JOptionPane.showInputDialog(dialog, "지속 ms:", "500")?.toLongOrNull() ?: 500L
                    val easings = arrayOf("linear", "easeIn", "easeOut", "easeInOut")
                    val easing  = JOptionPane.showInputDialog(dialog, "이징:",
                        "이징", JOptionPane.PLAIN_MESSAGE, null, easings, easings[0]) as? String ?: "linear"
                    val eff = DecEffect(type = type, startMs = startMs, durationMs = durMs, easing = easing)
                    effectsList.add(eff)
                    effectsModel.addRow(arrayOf(eff.type, eff.startMs, eff.durationMs, eff.easing))
                }
            })
            add(JButton("삭제").apply {
                addActionListener {
                    val row = effectsTable.selectedRow
                    if (row >= 0) { effectsModel.removeRow(row); effectsList.removeAt(row) }
                }
            })
        }
        val effPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            add(effScroll, BorderLayout.CENTER)
            add(efBtnPanel, BorderLayout.SOUTH)
        }
        tabs.addTab("이펙트", effPanel)

        // ── 하단 버튼 ─────────────────────────────────────────────────────────
        val btnOk = JButton("저장").apply {
            font = font.deriveFont(Font.BOLD)
            addActionListener {
                runCatching {
                    result = initial.copy(
                        id         = tfId.text.trim(),
                        image      = tfImage.text.trim(),
                        timeMs     = tfTimeMs.text.trim().toLong(),
                        durationMs = tfDurMs.text.trim().toLong(),
                        x          = tfX.text.trim().toFloat(),
                        y          = tfY.text.trim().toFloat(),
                        width      = tfW.text.trim().toFloat(),
                        height     = tfH.text.trim().toFloat(),
                        pivotX     = tfPivX.text.trim().toFloat(),
                        pivotY     = tfPivY.text.trim().toFloat(),
                        opacity    = tfOpacity.text.trim().toFloat(),
                        rotation   = tfRotation.text.trim().toFloat(),
                        scaleX     = tfScaleX.text.trim().toFloat(),
                        scaleY     = tfScaleY.text.trim().toFloat(),
                        depth      = tfDepth.text.trim().toInt(),
                        effects    = effectsList.toList()
                    )
                }.onFailure { ex ->
                    JOptionPane.showMessageDialog(dialog,
                        "입력 오류: ${ex.message}", "오류", JOptionPane.ERROR_MESSAGE)
                    return@addActionListener
                }
                dialog.dispose()
            }
        }
        val btnCancel = JButton("취소").apply { addActionListener { dialog.dispose() } }

        val bottomPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(btnCancel); add(btnOk)
        }
        dialog.contentPane.layout = BorderLayout()
        (dialog.contentPane as java.awt.Container).add(tabs, BorderLayout.CENTER)
        (dialog.contentPane as java.awt.Container).add(bottomPanel, BorderLayout.SOUTH)

        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true

        return result
    }
}
