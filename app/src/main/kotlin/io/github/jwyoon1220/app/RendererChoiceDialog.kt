package io.github.jwyoon1220.app

import io.github.jwyoon1220.engine.GLFWWindow
import io.github.jwyoon1220.engine.RenderApi
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Frame
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.WindowConstants

/**
 * 게임 시작 전(GLFW/LWJGL을 건드리기 전) 렌더러 백엔드를 고르는 모달 Swing 다이얼로그.
 * GLFW 창이 생기기 전에 떠야 하므로 순수 Swing으로만 구현합니다.
 */
object RendererChoiceDialog {
    /** @return 고른 렌더러. 창을 닫아 취소했으면 null. */
    fun choose(default: RenderApi): RenderApi? {
        var result: RenderApi? = null
        SwingUtilities.invokeAndWait {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

            val vulkanSupported = runCatching { GLFWWindow.isVulkanSupported() }.getOrDefault(false)

            val dialog = JDialog(null as Frame?, "StelLane - 렌더러 선택", true)
            dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE

            val panel = JPanel(BorderLayout(0, 16))
            panel.border = BorderFactory.createEmptyBorder(20, 24, 20, 24)

            val title = JLabel("렌더러를 선택하세요", JLabel.CENTER)
            title.font = title.font.deriveFont(Font.BOLD, 16f)
            panel.add(title, BorderLayout.NORTH)

            val buttonPanel = JPanel(GridLayout(1, 2, 12, 0))

            val glButton = JButton("<html><center><b>OpenGL</b><br><small>안정적 · 기본값</small></center></html>")
            val vkButton = JButton("<html><center><b>Vulkan</b><br><small>실험적 · ImGui 미지원</small></center></html>")
            glButton.preferredSize = Dimension(170, 64)
            vkButton.preferredSize = Dimension(170, 64)
            vkButton.isEnabled = vulkanSupported
            if (!vulkanSupported) vkButton.toolTipText = "이 시스템에서 Vulkan을 지원하지 않습니다"

            glButton.addActionListener { result = RenderApi.OPENGL; dialog.dispose() }
            vkButton.addActionListener { result = RenderApi.VULKAN; dialog.dispose() }

            buttonPanel.add(glButton)
            buttonPanel.add(vkButton)
            panel.add(buttonPanel, BorderLayout.CENTER)

            dialog.contentPane = panel
            dialog.isResizable = false
            dialog.pack()
            dialog.setLocationRelativeTo(null)
            // Enter 키로 바로 선택할 수 있도록 마지막 선택값(또는 지원 안 되면 OpenGL)을 기본 버튼으로.
            dialog.rootPane.defaultButton = if (default == RenderApi.VULKAN && vulkanSupported) vkButton else glButton

            dialog.isVisible = true // 모달 — dispose() 될 때까지 여기서 블록
        }
        return result
    }
}
