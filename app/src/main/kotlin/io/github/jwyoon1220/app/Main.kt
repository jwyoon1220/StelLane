package io.github.jwyoon1220.app

import io.github.jwyoon1220.app.state.MainMenuState
import io.github.jwyoon1220.core.StateManager
import io.github.jwyoon1220.core.song.SongManager
import io.github.jwyoon1220.engine.GameLoop
import io.github.jwyoon1220.engine.InputManager
import io.github.jwyoon1220.engine.RenderPanel
import io.github.jwyoon1220.engine.VideoBackground
import io.github.jwyoon1220.engine.pool.ObjectPool
import io.github.jwyoon1220.engine.pool.VisualNote
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities

fun main() {
    SwingUtilities.invokeLater {
        val frame = JFrame("StelLane - Rhythm Game")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.setSize(1280, 720)
        frame.layout = BorderLayout()
        frame.setLocationRelativeTo(null)

        // ── 공유 의존성 생성 ─────────────────────────────────────────────────
        val stateManager    = StateManager()

        // VLC 콜백 방식 (Canvas 없음 → heavyweight/lightweight 충돌 없음)
        val videoBackground = VideoBackground.create()

        // RenderPanel: 비디오 프레임 + 게임 UI를 하나의 Swing 컴포넌트에서 처리
        val renderPanel = RenderPanel(stateManager, videoBackground)

        val notePool = ObjectPool(
            factory = { VisualNote() },
            reset   = { vn -> vn.active = false; vn.held = false }
        )

        val inputManager = InputManager(renderPanel)

        val workingDir  = File(System.getProperty("user.dir"))
        val songManager = SongManager(workingDir)

        val ctx = GameContext(stateManager, songManager, videoBackground, notePool, inputManager)

        // ── 화면 구성 ────────────────────────────────────────────────────────
        // RenderPanel 하나가 전체 콘텐츠 영역을 담당 (GlassPane / Canvas 불필요)
        frame.add(renderPanel, BorderLayout.CENTER)

        // 비레인 키(Esc, Enter, Space, 화살표 등)를 현재 State 에 전달
        renderPanel.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent)  { stateManager.currentState?.keyPressed(e)  }
            override fun keyReleased(e: KeyEvent) { stateManager.currentState?.keyReleased(e) }
        })

        // ── 초기 화면: MainMenu ───────────────────────────────────────────────
        songManager.load()
        stateManager.changeState(MainMenuState(ctx))

        // ── 게임 루프 시작 ────────────────────────────────────────────────────
        val gameLoop = GameLoop(stateManager, renderPanel)
        gameLoop.start()

        frame.isVisible = true
        renderPanel.requestFocusInWindow()   // InputMap/KeyAdapter 가 동작하도록 포커스 요청

        // ── 종료 시 리소스 해제 ───────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(Thread {
            gameLoop.stop()
            videoBackground.release()
        })
    }
}
