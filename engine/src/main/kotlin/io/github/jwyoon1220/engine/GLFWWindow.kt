package io.github.jwyoon1220.engine

import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.glfw.GLFWImage
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.NULL
import org.slf4j.LoggerFactory

/**
 * 창이 사용하는 그래픽 API. [RenderApi.VULKAN]인 경우 GLFW는 OpenGL 컨텍스트를 만들지 않고
 * (`GLFW_CLIENT_API = GLFW_NO_API`) 대신 창 핸들만 제공 — Vulkan 서피스는 백엔드가 직접 만듭니다.
 */
enum class RenderApi { OPENGL, VULKAN }

/**
 * GLFW 윈도우 래퍼. [api]에 따라 OpenGL 컨텍스트를 갖거나(OPENGL), 컨텍스트 없이
 * 창 핸들만 제공합니다(VULKAN — VulkanBackend가 서피스/스왑체인을 직접 소유).
 *
 * ## 스레드 규칙
 * - [createWindow], [swapBuffers], [destroy] 는 반드시 메인 스레드에서 호출.
 * - OpenGL 드로우콜은 [createWindow] 호출 스레드(메인)에서만 유효.
 *
 * ## 좌표계
 * - [framebufferWidth] / [framebufferHeight] = 실제 픽셀 (HiDPI에서 논리 크기 ≠ FB 크기).
 * - 입력 콜백의 x/y 는 **논리(screen) 좌표** — Renderer가 FB 스케일로 변환.
 */
class GLFWWindow private constructor(val handle: Long, val api: RenderApi = RenderApi.OPENGL) {

    private val log = LoggerFactory.getLogger(GLFWWindow::class.java)

    // ── 프레임버퍼 크기 (실제 픽셀) ─────────────────────────────────────────
    var framebufferWidth:  Int = 0; private set
    var framebufferHeight: Int = 0; private set

    // ── 논리 창 크기 (screen coordinate) ────────────────────────────────────
    var logicalWidth:  Int = 0; private set
    var logicalHeight: Int = 0; private set

    // ── 입력 콜백 (GameLoop / InputManager에서 등록) ─────────────────────────
    /** key: GLFW_KEY_*, action: GLFW_PRESS/RELEASE/REPEAT, mods: GLFW_MOD_* */
    var onKey:         ((key: Int, action: Int, mods: Int) -> Unit)? = null
    /** Unicode 문자 입력 (텍스트 필드용) */
    var onChar:        ((codepoint: Int) -> Unit)? = null
    var onMouseButton: ((button: Int, action: Int, mods: Int) -> Unit)? = null
    /** 스크린 좌표 */
    var onCursorPos:   ((x: Double, y: Double) -> Unit)? = null
    var onScroll:      ((dx: Double, dy: Double) -> Unit)? = null
    var onResize:      ((logW: Int, logH: Int, fbW: Int, fbH: Int) -> Unit)? = null

    // ── 현재 커서 위치 (논리 좌표) ───────────────────────────────────────────
    var cursorX: Double = 0.0; private set
    var cursorY: Double = 0.0; private set

    // ── 창 모드 ──────────────────────────────────────────────────────────────
    private var currentMode: WindowMode = WindowMode.WINDOWED
    /** 창 모드 변경 전 windowed 위치/크기 복원용 */
    private var savedX = 100; private var savedY = 100
    private var savedW = 1280; private var savedH = 720

    init {
        // 프레임버퍼 크기 초기화
        stackPush().use { stack ->
            val fw = stack.mallocInt(1)
            val fh = stack.mallocInt(1)
            glfwGetFramebufferSize(handle, fw, fh)
            framebufferWidth  = fw[0]
            framebufferHeight = fh[0]

            val lw = stack.mallocInt(1)
            val lh = stack.mallocInt(1)
            glfwGetWindowSize(handle, lw, lh)
            logicalWidth  = lw[0]
            logicalHeight = lh[0]
        }

        // ── 콜백 등록 ──────────────────────────────────────────────────────
        glfwSetKeyCallback(handle) { _, key, _, action, mods ->
            onKey?.invoke(key, action, mods)
        }

        glfwSetCharCallback(handle) { _, codepoint ->
            onChar?.invoke(codepoint)
        }

        glfwSetMouseButtonCallback(handle) { _, button, action, mods ->
            onMouseButton?.invoke(button, action, mods)
        }

        glfwSetCursorPosCallback(handle) { _, x, y ->
            cursorX = x; cursorY = y
            onCursorPos?.invoke(x, y)
        }

        glfwSetScrollCallback(handle) { _, dx, dy ->
            onScroll?.invoke(dx, dy)
        }

        glfwSetFramebufferSizeCallback(handle) { _, w, h ->
            framebufferWidth  = w
            framebufferHeight = h
            glViewport(0, 0, w, h)
        }

        glfwSetWindowSizeCallback(handle) { _, w, h ->
            logicalWidth  = w
            logicalHeight = h
            onResize?.invoke(w, h, framebufferWidth, framebufferHeight)
        }
    }

    // ── 공개 API ─────────────────────────────────────────────────────────────

    /** 메인 루프에서 매 프레임 호출. 반드시 메인 스레드. */

    /**
     * 렌더링 완료 후 호출. VSync 대기 포함 (glfwSwapInterval(1) 기준).
     * Vulkan 모드에서는 no-op — 프레젠테이션은 VulkanBackend가 vkQueuePresentKHR로 직접 수행합니다.
     */
    fun swapBuffers() { if (api == RenderApi.OPENGL) glfwSwapBuffers(handle) }

    fun shouldClose(): Boolean = glfwWindowShouldClose(handle)

    fun requestClose() = glfwSetWindowShouldClose(handle, true)

    /** 창 모드 전환. 반드시 메인 스레드. */
    fun applyMode(mode: WindowMode) {
        if (mode == currentMode) return
        val monitor = glfwGetPrimaryMonitor()
        val vidMode = glfwGetVideoMode(monitor) ?: return

        when (mode) {
            WindowMode.WINDOWED -> {
                // 전체화면 → windowed
                glfwSetWindowMonitor(handle, NULL, savedX, savedY, savedW, savedH, GLFW_DONT_CARE)
                glfwSetWindowAttrib(handle, GLFW_DECORATED, GLFW_TRUE)
            }
            WindowMode.BORDERLESS -> {
                // 현재 위치/크기 저장 (windowed일 때만)
                if (currentMode == WindowMode.WINDOWED) saveWindowedGeometry()
                glfwSetWindowAttrib(handle, GLFW_DECORATED, GLFW_FALSE)
                glfwSetWindowMonitor(handle, NULL, 0, 0,
                    vidMode.width(), vidMode.height(), GLFW_DONT_CARE)
            }
            WindowMode.EXCLUSIVE -> {
                if (currentMode == WindowMode.WINDOWED) saveWindowedGeometry()
                // 독점 전체화면: glfwSetWindowMonitor에 monitor 전달
                glfwSetWindowMonitor(handle, monitor, 0, 0,
                    vidMode.width(), vidMode.height(), vidMode.refreshRate())
            }
        }
        currentMode = mode
        log.info("Window mode → {}", mode)
    }

    fun getMode(): WindowMode = currentMode

    /** 창 제목 변경 */
    var title: String = ""
        set(value) { field = value; glfwSetWindowTitle(handle, value) }

    /** 현재 커서 위치를 스크린(절대) 좌표로 반환. 메인 스레드에서만 호출. */
    fun getScreenCursorPos(): Pair<Int, Int> {
        return stackPush().use { stack ->
            val wx = stack.mallocInt(1)
            val wy = stack.mallocInt(1)
            glfwGetWindowPos(handle, wx, wy)
            Pair(wx[0] + cursorX.toInt(), wy[0] + cursorY.toInt())
        }
    }

    /** 현재 커서 위치를 논리 좌표로 반환 (Pair<x, y>) */
    fun getCursorPos(): Pair<Double, Double> = Pair(cursorX, cursorY)

    /** VSync 설정. 0=꺼짐, 1=활성화 */
    fun setVSync(interval: Int) = glfwSwapInterval(interval)

    /** 콜백 해제 → 윈도우 파괴 → GLFW 종료. 메인 스레드에서 호출. */
    fun destroy() {
        glfwFreeCallbacks(handle)
        glfwDestroyWindow(handle)
        glfwTerminate()
        glfwSetErrorCallback(null)?.free()
        log.info("GLFWWindow destroyed")
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private fun saveWindowedGeometry() {
        stackPush().use { stack ->
            val x = stack.mallocInt(1); val y = stack.mallocInt(1)
            val w = stack.mallocInt(1); val h = stack.mallocInt(1)
            glfwGetWindowPos(handle, x, y)
            glfwGetWindowSize(handle, w, h)
            savedX = x[0]; savedY = y[0]
            savedW = w[0]; savedH = h[0]
        }
    }

    // ── 정적 팩토리 ──────────────────────────────────────────────────────────

    companion object {
        private val log = LoggerFactory.getLogger(GLFWWindow::class.java)

        /**
         * GLFW 초기화 + 윈도우 생성 + GL 컨텍스트 활성화.
         * 반드시 **메인 스레드**에서 호출.
         *
         * @param title    창 제목
         * @param width    초기 논리 너비 (WindowMode.WINDOWED일 때만 의미있음)
         * @param height   초기 논리 높이
         * @param mode     초기 창 모드
         * @param vSync    VSync 활성화 여부 (기본 true, OpenGL에서만 의미 있음)
         * @param api      그래픽 API (기본 OPENGL). VULKAN이면 GL 컨텍스트를 만들지 않습니다.
         */
        fun createWindow(
            title:  String,
            width:  Int         = 1280,
            height: Int         = 720,
            mode:   WindowMode  = WindowMode.WINDOWED,
            vSync:  Boolean     = true,
            api:    RenderApi   = RenderApi.OPENGL
        ): GLFWWindow {
            // 에러 콜백 설정 (초기화 전 먼저)
            GLFWErrorCallback.createPrint(System.err).set()

            check(glfwInit()) { "GLFW 초기화 실패" }

            if (api == RenderApi.OPENGL) {
                // OpenGL 3.3 Core Profile
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
                glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
                glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)  // macOS 대응 (Windows는 무해)
                glfwWindowHint(GLFW_STENCIL_BITS, 8)                   // NanoVG 스텐실 필요
            } else {
                // Vulkan은 GLFW가 컨텍스트를 만들지 않아야 함 — 서피스는 VulkanBackend가 직접 생성
                glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
            }
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)                    // 창 위치 설정 후 표시

            val monitor = glfwGetPrimaryMonitor()
            val vidMode = checkNotNull(glfwGetVideoMode(monitor)) { "VideoMode 없음" }

            // 모드에 따라 창 생성 파라미터 결정
            val (actualW, actualH, actualMonitor) = when (mode) {
                WindowMode.WINDOWED   -> Triple(width, height, NULL)
                WindowMode.BORDERLESS -> Triple(vidMode.width(), vidMode.height(), NULL)
                WindowMode.EXCLUSIVE  -> Triple(vidMode.width(), vidMode.height(), monitor)
            }

            val handle = glfwCreateWindow(actualW, actualH, title, actualMonitor, NULL)
            check(handle != NULL) { "GLFW 윈도우 생성 실패" }

            // 아이콘 설정
            setWindowIcon(handle)

            // WINDOWED: 화면 중앙
            if (mode == WindowMode.WINDOWED) {
                val cx = (vidMode.width()  - width)  / 2
                val cy = (vidMode.height() - height) / 2
                glfwSetWindowPos(handle, cx, cy)
            }

            // BORDERLESS: 무장식
            if (mode == WindowMode.BORDERLESS) {
                glfwSetWindowAttrib(handle, GLFW_DECORATED, GLFW_FALSE)
            }

            if (api == RenderApi.OPENGL) {
                glfwMakeContextCurrent(handle)
                GL.createCapabilities()
                glfwSwapInterval(if (vSync) 1 else 0)
            }

            glfwShowWindow(handle)
            log.info("GLFWWindow created: {}×{} mode={} api={} vSync={}", actualW, actualH, mode, api, vSync)

            val win = GLFWWindow(handle, api)
            win.currentMode = mode
            return win
        }

        private fun setWindowIcon(handle: Long) {
            try {
                GLFWWindow::class.java.getResourceAsStream("/icon.png")?.use { stream ->
                    val bytes = stream.readAllBytes()
                    val buffer = org.lwjgl.system.MemoryUtil.memAlloc(bytes.size)
                    buffer.put(bytes)
                    buffer.flip()
                    
                    stackPush().use { stack ->
                        val w = stack.mallocInt(1)
                        val h = stack.mallocInt(1)
                        val comp = stack.mallocInt(1)
                        val imageBuffer = org.lwjgl.stb.STBImage.stbi_load_from_memory(buffer, w, h, comp, 4)
                        if (imageBuffer != null) {
                            val image = GLFWImage.malloc(stack)
                            image.set(w.get(0), h.get(0), imageBuffer)
                            val images = GLFWImage.malloc(1, stack)
                            images.put(0, image)
                            glfwSetWindowIcon(handle, images)
                            org.lwjgl.stb.STBImage.stbi_image_free(imageBuffer)
                            LoggerFactory.getLogger(GLFWWindow::class.java).info("Window icon loaded successfully.")
                        }
                    }
                    org.lwjgl.system.MemoryUtil.memFree(buffer)
                }
            } catch (e: Exception) {
                LoggerFactory.getLogger(GLFWWindow::class.java).warn("Failed to load window icon: {}", e.message)
            }
        }
    }
}
