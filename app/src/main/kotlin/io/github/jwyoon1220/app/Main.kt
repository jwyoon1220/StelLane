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
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Options
import org.apache.commons.cli.ParseException
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities

private val log = LoggerFactory.getLogger("io.github.jwyoon1220.app.Main")

fun main(args: Array<String>) {
    val options = Options().apply {
        addOption("d", "debug",   false, "DEBUG 레벨 로깅 활성화")
        addOption("c", "console", false, "콘솔 로그 출력 활성화")
    }

    val cmd = try {
        DefaultParser().parse(options, args)
    } catch (e: ParseException) {
        HelpFormatter().printHelp("StelLane", options)
        return
    }

    LoggingConfig.apply(
        debug   = cmd.hasOption("debug"),
        console = cmd.hasOption("console")
    )

    log.info("StelLane 시작 (debug={}, console={})", cmd.hasOption("debug"), cmd.hasOption("console"))

    SwingUtilities.invokeLater {
        val frame = JFrame("StelLane - Rhythm Game")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

        // 저장된 창 모드 적용 (기본 BORDERLESS)
        when (AppSettings.windowMode) {
            WindowMode.WINDOWED -> {
                frame.isUndecorated = false
                frame.setSize(1280, 720)
                frame.setLocationRelativeTo(null)
            }
            WindowMode.BORDERLESS, WindowMode.EXCLUSIVE -> {
                frame.isUndecorated = true
                frame.extendedState = JFrame.MAXIMIZED_BOTH
            }
        }
        frame.layout = BorderLayout()

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

        val windowManager = WindowManager(frame, renderPanel)
        val ctx = GameContext(stateManager, songManager, videoBackground, notePool, inputManager, windowManager)

        // ── 화면 구성 ────────────────────────────────────────────────────────
        // RenderPanel 하나가 전체 콘텐츠 영역을 담당 (GlassPane / Canvas 불필요)
        frame.add(renderPanel, BorderLayout.CENTER)

        // 비레인 키(Esc, Enter, Space, 화살표 등)를 현재 State 에 전달
        renderPanel.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent)  { stateManager.currentState?.keyPressed(e)  }
            override fun keyReleased(e: KeyEvent) { stateManager.currentState?.keyReleased(e) }
        })

        // 마우스 이벤트를 현재 State 에 전달 (화면→논리 좌표 역변환 포함)
        renderPanel.addMouseListener(object : MouseAdapter() {
            private fun remap(e: MouseEvent): MouseEvent {
                val p = renderPanel.toLogical(e.x, e.y)
                return MouseEvent(e.component, e.id, e.`when`, e.modifiersEx,
                    p.x, p.y, e.xOnScreen, e.yOnScreen, e.clickCount, e.isPopupTrigger, e.button)
            }
            override fun mousePressed(e: MouseEvent)  { stateManager.currentState?.mousePressed(remap(e))  }
            override fun mouseClicked(e: MouseEvent)  { stateManager.currentState?.mouseClicked(remap(e))  }
            override fun mouseReleased(e: MouseEvent) { stateManager.currentState?.mouseReleased(remap(e)) }
        })
        renderPanel.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val p = renderPanel.toLogical(e.x, e.y)
                val remapped = MouseEvent(e.component, e.id, e.`when`, e.modifiersEx,
                    p.x, p.y, e.xOnScreen, e.yOnScreen, e.clickCount, e.isPopupTrigger, e.button)
                stateManager.currentState?.mouseDragged(remapped)
            }
        })

        // ── 초기 화면: MainMenu ───────────────────────────────────────────────
        songManager.load()
        stateManager.changeState(MainMenuState(ctx))

        // ── 게임 루프 시작 ────────────────────────────────────────────────────
        val gameLoop = GameLoop(stateManager, renderPanel)
        gameLoop.start()

        frame.isVisible = true
        renderPanel.requestFocusInWindow()

        // 창 포커스를 얻을 때마다 renderPanel 에 키 포커스 재요청 (창 모드에서 타이틀바 클릭 후 키 입력 복구)
        frame.addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) {
                renderPanel.requestFocusInWindow()
            }
        })

        // 독점 전체화면은 창이 표시된 후 적용
        if (AppSettings.windowMode == WindowMode.EXCLUSIVE) {
            val gd = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            if (gd.isFullScreenSupported) gd.fullScreenWindow = frame
        }

        // ── 종료 시 리소스 해제 ───────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(Thread {
            gameLoop.stop()
            videoBackground.release()
        })
    }
}
