package io.github.jwyoon1220.app.ui

import io.github.jwyoon1220.app.AppSettings
import io.github.jwyoon1220.app.WindowManager
import io.github.jwyoon1220.app.WindowMode
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton

class SettingsDialog(
    owner: JFrame,
    private val windowManager: WindowManager
) : JDialog(owner, "설정", true) {

    private val bg = Color(18, 18, 32)
    private val fg = Color(210, 210, 230)
    private val accent = Color(150, 100, 255)

    private var selectedMode = AppSettings.windowMode

    init {
        isUndecorated = false
        preferredSize = Dimension(420, 260)

        contentPane.background = bg
        layout = BorderLayout(0, 0)

        // ── 타이틀 ──────────────────────────────────────────────────────────
        val titleLabel = JLabel("설정").apply {
            foreground = accent
            font = Font("SansSerif", Font.BOLD, 18)
            border = BorderFactory.createEmptyBorder(16, 20, 8, 20)
        }
        add(titleLabel, BorderLayout.NORTH)

        // ── 내용 ──────────────────────────────────────────────────────────
        val content = JPanel(GridBagLayout()).apply { background = bg }
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 20, 4, 20)
            gridx = 0; gridy = 0
        }

        val sectionLabel = JLabel("창 모드").apply {
            foreground = Color(130, 100, 200)
            font = Font("SansSerif", Font.BOLD, 13)
        }
        content.add(sectionLabel, gbc)

        val buttonGroup = ButtonGroup()
        val modes = listOf(
            WindowMode.WINDOWED   to "창 모드 (리사이즈 가능)",
            WindowMode.BORDERLESS to "윈도우 전체화면 (테두리 없음)",
            WindowMode.EXCLUSIVE  to "독점 전체화면"
        )

        modes.forEach { (mode, label) ->
            gbc.gridy++
            val rb = JRadioButton(label).apply {
                background = bg
                foreground = fg
                font = Font("SansSerif", Font.PLAIN, 13)
                isSelected = (mode == selectedMode)
                addActionListener { selectedMode = mode }
            }
            buttonGroup.add(rb)
            content.add(rb, gbc)
        }

        add(content, BorderLayout.CENTER)

        // ── 버튼 ──────────────────────────────────────────────────────────
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 10)).apply { background = bg }

        val applyBtn = JButton("적용").apply {
            background = Color(80, 50, 160)
            foreground = Color.WHITE
            font = Font("SansSerif", Font.BOLD, 13)
            isFocusPainted = false
            addActionListener {
                dispose()
                windowManager.applyMode(selectedMode)
            }
        }
        val cancelBtn = JButton("취소").apply {
            background = Color(50, 50, 70)
            foreground = fg
            font = Font("SansSerif", Font.PLAIN, 13)
            isFocusPainted = false
            addActionListener { dispose() }
        }
        buttons.add(cancelBtn)
        buttons.add(applyBtn)
        add(buttons, BorderLayout.SOUTH)

        pack()
        setLocationRelativeTo(owner)
    }
}
