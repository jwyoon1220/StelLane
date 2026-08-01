// SPDX-License-Identifier: GPL-3.0-only
package io.github.jwyoon1220.app

import io.github.jwyoon1220.app.ecs.EulaScene
import io.github.jwyoon1220.app.ecs.MainMenuScene
import io.github.jwyoon1220.core.song.SongManager
import io.github.jwyoon1220.engine.GLFWWindow
import io.github.jwyoon1220.engine.GameLoop
import io.github.jwyoon1220.engine.HitSound
import io.github.jwyoon1220.engine.ImGuiManager
import io.github.jwyoon1220.engine.InputManager
import io.github.jwyoon1220.engine.RenderApi
import io.github.jwyoon1220.engine.Renderer
import io.github.jwyoon1220.engine.SceneRouter
import io.github.jwyoon1220.engine.VideoBackground
import io.github.jwyoon1220.engine.data.pool.ObjectPool
import io.github.jwyoon1220.engine.data.pool.VisualNote
import io.github.jwyoon1220.app.render.NoteRenderer
import io.github.jwyoon1220.engine.WindowMode
import io.github.jwyoon1220.engine.multiplayer.MultiplayerCacheManager
import io.github.jwyoon1220.engine.render.RendererFactory
import io.github.jwyoon1220.engine.vulkan.VulkanBackend
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Options
import org.apache.commons.cli.ParseException
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("io.github.jwyoon1220.app.Main")

fun main(args: Array<String>) {
    val options = Options().apply {
        addOption("d", "debug",   false, "DEBUG 레벨 로깅 활성화")
        addOption("c", "console", false, "콘솔 로그 출력 활성화")
        addOption("v", "vulkan",  false, "실험적 Vulkan 렌더러 백엔드 사용 (기본은 OpenGL/NanoVG). ImGui 기반 UI는 아직 지원하지 않습니다. 지정하면 렌더러 선택 창을 건너뜁니다.")
        addOption("g", "opengl",  false, "OpenGL 렌더러 백엔드 사용. 지정하면 렌더러 선택 창을 건너뜁니다.")
        addOption(null, "screenshot", false, "[디버그] 4초 후 스왑체인/프레임버퍼를 *_debug_screenshot.png로 저장하고 종료합니다.")
    }

    val cmd = try {
        DefaultParser().parse(options, args)
    } catch (_: ParseException) {
        HelpFormatter().printHelp("StelLane", options)
        return
    }

    LoggingConfig.apply(
        debug   = cmd.hasOption("debug"),
        console = cmd.hasOption("console")
    )

    // --vulkan/--opengl이 명시되면 그 값을 그대로 쓰고(자동화 테스트/스크립트용 — 스윙 창이 뜨면 블로킹됨),
    // 아니면 GLFW/LWJGL을 아직 건드리기 전에 렌더러 선택 창을 띄워 사용자가 고르게 합니다.
    val useVulkan = when {
        cmd.hasOption("vulkan") -> true
        cmd.hasOption("opengl") -> false
        else -> {
            val choice = RendererChoiceDialog.choose(AppSettings.preferredRenderApi)
            if (choice == null) {
                logger.info("[Main] 렌더러 선택 취소 — 종료")
                exitProcess(0)
            }
            AppSettings.preferredRenderApi = choice
            choice == RenderApi.VULKAN
        }
    }
    logger.info("StelLane 시작 (debug={}, console={}, vulkan={})", cmd.hasOption("debug"), cmd.hasOption("console"), useVulkan)

    // 멀티플레이어 캐시 만료 항목 정리 (백그라운드, 게임 루프와 무관)
    thread(start = true, isDaemon = true, name = "cache-cleaner") {
        MultiplayerCacheManager.cleanExpired()
    }

    val window = GLFWWindow.createWindow(
        title  = "StelLane",
        width  = 1280,
        height = 720,
        mode   = AppSettings.windowMode,
        vSync  = AppSettings.vSync,
        api    = if (useVulkan) RenderApi.VULKAN else RenderApi.OPENGL
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
    }.thenAccept { logger.info("[Main] NotePool 초기 할당 완료: poolSize={}", notePool.poolSize) }

    val renderer     = Renderer(window, sceneRouter, videoBackground)
    if (useVulkan) {
        RendererFactory.register("vulkan") { VulkanBackend() }
        renderer.backendId = "vulkan"
    }
    val inputManager = InputManager(window, renderer)

    val workingDir   = File(System.getProperty("user.dir"))
    val songManager  = SongManager(workingDir)

    val windowManager = WindowManager(window, renderer)
    val ctx = GameContext(sceneRouter, songManager, videoBackground, notePool, inputManager, windowManager, NoteRenderer())

    renderer.init()
    ctx.renderer = renderer
    HitSound.volume = AppSettings.hitSoundVolume

    // ImGui는 imgui-java-lwjgl3(OpenGL 전용)에 의존 — Vulkan 모드(GL 컨텍스트 없음)에서는 건너뜁니다.
    // ImGui 기반 UI(에디터의 가져오기/내보내기 다이얼로그, 장식 편집 등)는 Vulkan에서 아직 지원하지 않습니다.
    val imGuiManager = if (!useVulkan) ImGuiManager(window.handle).also { it.init() } else null
    renderer.imGuiManager   = imGuiManager
    inputManager.imGuiManager = imGuiManager

    inputManager.stateKeyPressed  = { key, mods -> sceneRouter.current?.keyPressed(key, mods) }
    inputManager.stateKeyReleased = { key, mods -> sceneRouter.current?.keyReleased(key, mods) }
    inputManager.stateKeyTyped    = { cp          -> sceneRouter.current?.keyTyped(cp) }
    inputManager.stateMousePressed  = { x, y, btn, mods -> sceneRouter.current?.mousePressed(x, y, btn, mods) }
    inputManager.stateMouseReleased = { x, y, btn, mods -> sceneRouter.current?.mouseReleased(x, y, btn, mods) }
    inputManager.stateMouseClicked  = { x, y, btn, mods -> sceneRouter.current?.mouseClicked(x, y, btn, mods) }
    inputManager.stateMouseDragged  = { x, y, btn       -> sceneRouter.current?.mouseDragged(x, y, btn) }
    inputManager.stateScroll        = { dy              -> sceneRouter.current?.mouseScrolled(dy) }

    songManager.load()
    val startScene = if (AppSettings.eulaAccepted) MainMenuScene(ctx) else EulaScene(ctx)
    sceneRouter.navigate(startScene)

    val gameLoop = GameLoop(window, sceneRouter, renderer, inputManager)
    ctx.gameLoop = gameLoop
    var screenshotSecondsElapsed = 0
    gameLoop.onFpsUpdate = { fps -> // Windowed 모드에서만 창 타이틀 변경
        if (AppSettings.windowMode == WindowMode.WINDOWED) {
            window.title = "StelLane  |  $fps FPS"
        }
        // --screenshot 디버그 훅 — Vulkan 큐 제출은 스레드 안전하지 않으므로 별도 스레드의 sleep이
        // 아니라 게임 루프와 같은 메인 스레드에서 도는 이 콜백(초당 1회)으로 타이밍을 잡습니다.
        // 백엔드 무관(Renderer.debugCaptureFrame이 위임) — OpenGL/Vulkan 픽셀 단위 비교에 씀.
        if (cmd.hasOption("screenshot")) {
            screenshotSecondsElapsed++
            if (screenshotSecondsElapsed == 4) {
                val name = if (useVulkan) "vulkan_debug_screenshot.png" else "opengl_debug_screenshot.png"
                renderer.debugCaptureFrame(File(workingDir, name).absolutePath)
            } else if (screenshotSecondsElapsed >= 5) {
                window.requestClose()
            }
        }
    }
    gameLoop.targetFPS = AppSettings.targetFps

    // Blocking
    gameLoop.start()

    // Exit of game
    imGuiManager?.dispose()
    renderer.destroy()
    window.destroy()
    videoBackground.release()
    logger.info("Game Exited.")
    exitProcess(0)
}
