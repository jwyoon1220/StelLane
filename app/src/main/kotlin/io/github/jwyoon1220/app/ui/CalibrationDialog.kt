package io.github.jwyoon1220.app.ui

import io.github.jwyoon1220.app.AppSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.event.ChangeListener

/**
 * 오디오/비디오 보정 (오프셋) 설정 다이얼로그.
 *
 * calibrationOffsetMs 양수 → 노트가 더 일찍 판정됨 (오디오가 늦게 들릴 때)
 * calibrationOffsetMs 음수 → 노트가 더 늦게 판정됨 (오디오가 일찍 들릴 때)
 */
class CalibrationDialog(owner: JFrame) : JDialog(owner, "오디오/비디오 보정", true) {

    private val bg     = Color(18, 18, 32)
    private val fg     = Color(210, 210, 230)
    private val accent = Color(150, 100, 255)

    private var pendingOffset = AppSettings.calibrationOffsetMs.coerceIn(-500L, 500L)

    init {
        preferredSize = Dimension(460, 280)
        contentPane.background = bg
        layout = BorderLayout(0, 0)

        // ── 타이틀 ──────────────────────────────────────────────────────────
        val titleLabel = JLabel("오디오/비디오 보정").apply {
            foreground = accent
            font = Font("SansSerif", Font.BOLD, 18)
            border = BorderFactory.createEmptyBorder(16, 20, 8, 20)
        }
        add(titleLabel, BorderLayout.NORTH)

        // ── 내용 ──────────────────────────────────────────────────────────
        val content = JPanel(GridBagLayout()).apply { background = bg }
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = Insets(6, 20, 6, 20)
            gridx = 0; gridy = 0; gridwidth = 2
        }

        content.add(JLabel("오프셋 (ms): 양수 = 오디오가 늦을 때, 음수 = 오디오가 빠를 때").apply {
            foreground = Color(160, 160, 180)
            font = Font("SansSerif", Font.PLAIN, 12)
        }, gbc)

        // 슬라이더 -500 ~ +500
        val slider = JSlider(-500, 500, pendingOffset.toInt()).apply {
            background = bg
            foreground = fg
            majorTickSpacing = 250
            minorTickSpacing = 50
            paintTicks = true
            paintLabels = true
            setUI(javax.swing.plaf.basic.BasicSliderUI(this))
        }

        // 숫자 입력
        val spinner = JSpinner(SpinnerNumberModel(pendingOffset.toInt(), -500, 500, 1)).apply {
            preferredSize = Dimension(80, 28)
            (editor as? JSpinner.DefaultEditor)?.textField?.apply {
                foreground = fg
                background = Color(35, 35, 55)
                font = Font("SansSerif", Font.PLAIN, 14)
                horizontalAlignment = SwingConstants.CENTER
            }
        }

        // 현재값 라벨
        val valueLabel = JLabel("${pendingOffset}ms").apply {
            foreground = Color(255, 220, 80)
            font = Font("SansSerif", Font.BOLD, 16)
            horizontalAlignment = SwingConstants.CENTER
        }

        // 상호 동기화
        val sliderListener = ChangeListener {
            val v = slider.value.toLong()
            if (pendingOffset != v) {
                pendingOffset = v
                spinner.value = v.toInt()
                valueLabel.text = "${v}ms"
            }
        }
        val spinnerListener = ChangeListener {
            val v = (spinner.value as Int).toLong()
            if (pendingOffset != v) {
                pendingOffset = v
                slider.value = v.toInt()
                valueLabel.text = "${v}ms"
            }
        }
        slider.addChangeListener(sliderListener)
        spinner.addChangeListener(spinnerListener)

        gbc.gridy++; gbc.gridwidth = 1
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        content.add(slider, gbc)

        gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        content.add(spinner, gbc)

        gbc.gridx = 0; gbc.gridy++; gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL
        content.add(valueLabel, gbc)

        gbc.gridy++
        content.add(JButton("0으로 초기화").apply {
            background = Color(50, 50, 70)
            foreground = fg
            font = Font("SansSerif", Font.PLAIN, 12)
            isFocusPainted = false
            addActionListener {
                slider.value = 0
            }
        }, gbc)

        add(content, BorderLayout.CENTER)

        // ── 버튼 ──────────────────────────────────────────────────────────
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 10)).apply { background = bg }

        val applyBtn = JButton("적용").apply {
            background = Color(80, 50, 160)
            foreground = Color.WHITE
            font = Font("SansSerif", Font.BOLD, 13)
            isFocusPainted = false
            addActionListener {
                AppSettings.calibrationOffsetMs = pendingOffset
                dispose()
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
