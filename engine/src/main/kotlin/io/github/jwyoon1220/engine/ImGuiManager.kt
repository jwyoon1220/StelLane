package io.github.jwyoon1220.engine

import imgui.ImFontConfig
import imgui.ImGui
import imgui.gl3.ImGuiImplGl3
import imgui.glfw.ImGuiImplGlfw
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiConfigFlags
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.slf4j.LoggerFactory

/**
 * Dear ImGui 생명주기 관리.
 *
 * - [init]   : GL 컨텍스트 현재화 이후 (renderer.init() 다음) 한 번 호출.
 * - [newFrame] + [render]: 매 프레임 렌더 루프에서 호출.
 * - [dispose]: 앱 종료 시 한 번 호출.
 *
 * [clearBackground]: ImGui만 표시하는 독립 창(예: 빌더)에서
 *                    배경을 단색으로 지울 때 사용.
 */
class ImGuiManager(private val windowHandle: Long) {

    private val log         = LoggerFactory.getLogger(ImGuiManager::class.java)
    private val imGuiGlfw   = ImGuiImplGlfw()
    private val imGuiGl3    = ImGuiImplGl3()

    fun init() {
        ImGui.createContext()

        // ── 마루 부리 폰트 (한국어 + ASCII) ─────────────────────────────────
        val io = ImGui.getIO()
        val fontBytes = runCatching {
            ImGuiManager::class.java.classLoader
                .getResourceAsStream("fonts/MaruBuri-Regular.ttf")
                ?.readBytes()
        }.getOrNull()
        if (fontBytes != null) {
            val cfg = ImFontConfig()
            cfg.oversampleH = 2
            cfg.oversampleV = 2
            io.fonts.addFontFromMemoryTTF(fontBytes, 16f, cfg, io.fonts.glyphRangesKorean)
            cfg.destroy()
            log.info("[ImGuiManager] MaruBuri 폰트 로드 완료")
        } else {
            io.fonts.addFontDefault()
            log.warn("[ImGuiManager] MaruBuri 폰트를 찾을 수 없음, 기본 폰트 사용")
        }

        io.apply {
            iniFilename = null                                   // imgui.ini 비활성화
            addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard)
        }
        ImGui.styleColorsDark()
        applyStelLaneTheme()

        imGuiGlfw.init(windowHandle, true)                      // GLFW 콜백 체이닝
        imGuiGl3.init("#version 330 core")
        log.info("[ImGuiManager] initialized")
    }

    /** 매 프레임: ImGui 렌더 커맨드 수집 시작. */
    fun newFrame() {
        imGuiGlfw.newFrame()
        ImGui.newFrame()
    }

    /** 매 프레임: 누적된 ImGui 커맨드를 OpenGL로 플러시. */
    fun render() {
        ImGui.render()
        imGuiGl3.renderDrawData(ImGui.getDrawData())
    }

    /** 빌더 등 단독 ImGui 창에서 배경을 지울 때 사용. */
    fun clearBackground(r: Float = 0.03f, g: Float = 0.02f, b: Float = 0.10f) {
        glClearColor(r, g, b, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
    }

    fun dispose() {
        imGuiGl3.dispose()
        imGuiGlfw.dispose()
        ImGui.destroyContext()
        log.info("[ImGuiManager] disposed")
    }

    // ── StelLane 퍼플 테마 ──────────────────────────────────────────────────

    private fun applyStelLaneTheme() {
        val s = ImGui.getStyle()
        s.windowRounding = 4f
        s.frameRounding  = 3f
        s.grabRounding   = 3f
        s.popupRounding  = 3f

        fun col(idx: Int, r: Float, g: Float, b: Float, a: Float = 1f) =
            s.setColor(idx, r, g, b, a)

        col(ImGuiCol.WindowBg,          0.03f, 0.02f, 0.08f, 0.96f)
        col(ImGuiCol.ChildBg,           0.04f, 0.02f, 0.10f, 0.80f)
        col(ImGuiCol.PopupBg,           0.06f, 0.04f, 0.16f, 0.95f)
        col(ImGuiCol.MenuBarBg,         0.05f, 0.03f, 0.18f, 1.00f)
        col(ImGuiCol.TitleBg,           0.05f, 0.03f, 0.18f, 1.00f)
        col(ImGuiCol.TitleBgActive,     0.18f, 0.10f, 0.45f, 1.00f)
        col(ImGuiCol.Header,            0.28f, 0.16f, 0.52f, 0.65f)
        col(ImGuiCol.HeaderHovered,     0.38f, 0.22f, 0.68f, 0.80f)
        col(ImGuiCol.HeaderActive,      0.44f, 0.27f, 0.74f, 1.00f)
        col(ImGuiCol.Button,            0.25f, 0.14f, 0.48f, 0.80f)
        col(ImGuiCol.ButtonHovered,     0.34f, 0.21f, 0.62f, 1.00f)
        col(ImGuiCol.ButtonActive,      0.40f, 0.26f, 0.70f, 1.00f)
        col(ImGuiCol.FrameBg,           0.09f, 0.06f, 0.18f, 0.80f)
        col(ImGuiCol.FrameBgHovered,    0.14f, 0.09f, 0.26f, 0.85f)
        col(ImGuiCol.FrameBgActive,     0.19f, 0.13f, 0.34f, 1.00f)
        col(ImGuiCol.CheckMark,         0.80f, 0.60f, 1.00f, 1.00f)
        col(ImGuiCol.SliderGrab,        0.54f, 0.34f, 0.84f, 1.00f)
        col(ImGuiCol.SliderGrabActive,  0.64f, 0.41f, 0.94f, 1.00f)
        col(ImGuiCol.Separator,         0.22f, 0.16f, 0.40f, 0.80f)
        col(ImGuiCol.SeparatorHovered,  0.34f, 0.24f, 0.54f, 0.90f)
        col(ImGuiCol.SeparatorActive,   0.44f, 0.30f, 0.68f, 1.00f)
        col(ImGuiCol.Tab,               0.14f, 0.08f, 0.30f, 0.86f)
        col(ImGuiCol.TabHovered,        0.36f, 0.22f, 0.66f, 0.80f)
        col(ImGuiCol.TabActive,         0.24f, 0.14f, 0.50f, 1.00f)
        col(ImGuiCol.ScrollbarGrab,     0.28f, 0.18f, 0.52f, 0.80f)
        col(ImGuiCol.ScrollbarGrabHovered, 0.36f, 0.22f, 0.66f, 1.00f)
        col(ImGuiCol.ScrollbarGrabActive,  0.44f, 0.28f, 0.74f, 1.00f)
    }
}
