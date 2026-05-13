package io.github.jwyoon1220.app

import io.github.jwyoon1220.app.state.MainMenuState
import io.github.jwyoon1220.core.song.SongManager
import io.github.jwyoon1220.engine.GLFWWindow
import io.github.jwyoon1220.engine.GameLoop
import io.github.jwyoon1220.engine.ImGuiManager
import io.github.jwyoon1220.engine.InputManager
import io.github.jwyoon1220.engine.Renderer
import io.github.jwyoon1220.engine.StateManager
import io.github.jwyoon1220.engine.VideoBackground
import io.github.jwyoon1220.engine.WindowMode
import io.github.jwyoon1220.engine.data.pool.ObjectPool
import io.github.jwyoon1220.engine.data.pool.VisualNote
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Options
import org.apache.commons.cli.ParseException
import org.slf4j.LoggerFactory
import java.io.File

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

    // ── GLFW 윈도우 생성 (메인 스레드) ────────────────────────────────────────
    val window = GLFWWindow.create(
        title  = "StelLane",
        width  = 1280,
        height = 720,
        mode   = AppSettings.windowMode,
        vSync  = false   // 직접 프레임 대기로 FPS 제어
    )

    // ── 공유 의존성 생성 ──────────────────────────────────────────────────────
    val stateManager    = StateManager()
    val videoBackground = VideoBackground.create()

    val notePool = ObjectPool(
        initialCapacity = 2048,
        factory = { VisualNote() },
        reset   = { vn -> vn.active = false; vn.held = false }
    )
    notePool.preAllocate(2048)

    val renderer     = Renderer(window, stateManager, videoBackground)
    val inputManager = InputManager(window, renderer)

    val workingDir   = File(System.getProperty("user.dir"))
    val songManager  = SongManager(workingDir)

    val windowManager = WindowManager(window)
    val ctx = GameContext(stateManager, songManager, videoBackground, notePool, inputManager, windowManager)

    // ── OpenGL / NanoVG 초기화 (메인 스레드, GL 컨텍스트 바인딩 후) ────────────
    renderer.init()
    ctx.renderer = renderer

    // ── Dear ImGui 초기화 (GL 컨텍스트 활성화 후) ──────────────────────────────
    val imGuiManager = ImGuiManager(window.handle)
    imGuiManager.init()
    renderer.imGuiManager   = imGuiManager
    inputManager.imGuiManager = imGuiManager

    // ── InputManager → State 콜백 연결 ───────────────────────────────────────
    inputManager.stateKeyPressed  = { key, mods -> stateManager.currentState?.keyPressed(key, mods) }
    inputManager.stateKeyReleased = { key, mods -> stateManager.currentState?.keyReleased(key, mods) }
    inputManager.stateKeyTyped    = { cp          -> stateManager.currentState?.keyTyped(cp) }
    inputManager.stateMousePressed  = { x, y, btn, mods -> stateManager.currentState?.mousePressed(x, y, btn, mods) }
    inputManager.stateMouseReleased = { x, y, btn, mods -> stateManager.currentState?.mouseReleased(x, y, btn, mods) }
    inputManager.stateMouseClicked  = { x, y, btn, mods -> stateManager.currentState?.mouseClicked(x, y, btn, mods) }
    inputManager.stateMouseDragged  = { x, y, btn       -> stateManager.currentState?.mouseDragged(x, y, btn) }
    inputManager.stateScroll        = { dy              -> stateManager.currentState?.mouseScrolled(dy) }

    // ── 초기 화면: MainMenu ──────────────────────────────────────────────────
    songManager.load()
    stateManager.changeState(MainMenuState(ctx))

    // ── 게임 루프 시작 (블로킹, 창이 닫힐 때까지 반환되지 않음) ─────────────────
    val gameLoop = GameLoop(window, stateManager, renderer)
    ctx.gameLoop = gameLoop
    gameLoop.onFpsUpdate = { fps -> window.title = "StelLane  |  $fps FPS" }
    gameLoop.targetFPS = AppSettings.targetFps
    gameLoop.start()   // ← 블로킹

    // ── 종료 시 리소스 해제 ───────────────────────────────────────────────────
    imGuiManager.dispose()
    renderer.destroy()
    window.destroy()
    videoBackground.release()
    Runtime.getRuntime().halt(0)   // VLC 네이티브 스레드 hang 방어
}
