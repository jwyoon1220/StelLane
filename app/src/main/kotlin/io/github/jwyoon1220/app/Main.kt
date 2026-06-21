package io.github.jwyoon1220.app

import io.github.jwyoon1220.app.ecs.MainMenuScene
import io.github.jwyoon1220.core.song.SongManager
import io.github.jwyoon1220.engine.GLFWWindow
import io.github.jwyoon1220.engine.GameLoop
import io.github.jwyoon1220.engine.ImGuiManager
import io.github.jwyoon1220.engine.InputManager
import io.github.jwyoon1220.engine.Renderer
import io.github.jwyoon1220.engine.SceneRouter
import io.github.jwyoon1220.engine.VideoBackground
import io.github.jwyoon1220.engine.data.pool.ObjectPool
import io.github.jwyoon1220.engine.data.pool.VisualNote
import io.github.jwyoon1220.app.render.NoteRenderer
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Options
import org.apache.commons.cli.ParseException
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CompletableFuture

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

    val window = GLFWWindow.create(
        title  = "StelLane",
        width  = 1280,
        height = 720,
        mode   = AppSettings.windowMode,
        vSync  = AppSettings.vSync
    )

    val sceneRouter     = SceneRouter()
    val videoBackground = VideoBackground.create().apply {
        setTargetVolumePercent((AppSettings.musicVolume * 100).toInt())
    }

    val notePool = ObjectPool(
        initialCapacity = 2048,
        factory = { VisualNote() },
        reset   = { vn -> vn.active = false; vn.held = false }
    )
    CompletableFuture.runAsync {
        notePool.preAllocate(2048)
    }.thenAccept { log.info("[Main] NotePool 초기 할당 완료: poolSize={}", notePool.poolSize) }

    val renderer     = Renderer(window, sceneRouter, videoBackground)
    val inputManager = InputManager(window, renderer)

    val workingDir   = File(System.getProperty("user.dir"))
    val songManager  = SongManager(workingDir)

    val windowManager = WindowManager(window)
    val ctx = GameContext(sceneRouter, songManager, videoBackground, notePool, inputManager, windowManager, NoteRenderer())

    renderer.init()
    ctx.renderer = renderer

    val imGuiManager = ImGuiManager(window.handle)
    imGuiManager.init()
    renderer.imGuiManager   = imGuiManager
    inputManager.imGuiManager = imGuiManager

    // ECS InputSnapshot 경로 외에 legacy 콜백도 유지 (다른 씬들이 여전히 사용)
    inputManager.stateKeyPressed  = { key, mods -> sceneRouter.current?.keyPressed(key, mods) }
    inputManager.stateKeyReleased = { key, mods -> sceneRouter.current?.keyReleased(key, mods) }
    inputManager.stateKeyTyped    = { cp          -> sceneRouter.current?.keyTyped(cp) }
    inputManager.stateMousePressed  = { x, y, btn, mods -> sceneRouter.current?.mousePressed(x, y, btn, mods) }
    inputManager.stateMouseReleased = { x, y, btn, mods -> sceneRouter.current?.mouseReleased(x, y, btn, mods) }
    inputManager.stateMouseClicked  = { x, y, btn, mods -> sceneRouter.current?.mouseClicked(x, y, btn, mods) }
    inputManager.stateMouseDragged  = { x, y, btn       -> sceneRouter.current?.mouseDragged(x, y, btn) }
    inputManager.stateScroll        = { dy              -> sceneRouter.current?.mouseScrolled(dy) }

    songManager.load()
    sceneRouter.navigate(MainMenuScene(ctx))

    val gameLoop = GameLoop(window, sceneRouter, renderer, inputManager)
    ctx.gameLoop = gameLoop
    gameLoop.onFpsUpdate = { fps -> window.title = "StelLane  |  $fps FPS" }
    gameLoop.targetFPS = AppSettings.targetFps
    gameLoop.start()

    imGuiManager.dispose()
    renderer.destroy()
    window.destroy()
    videoBackground.release()
    System.exit(0)
}
