package io.github.jwyoon1220.builder

import imgui.ImGui
import imgui.gl3.ImGuiImplGl3
import imgui.glfw.ImGuiImplGlfw
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiTreeNodeFlags
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImString
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.Song
import io.github.jwyoon1220.core.song.ChartParser
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_DONT_CARE
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_TRUE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor
import org.lwjgl.glfw.GLFW.glfwGetVideoMode
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwPollEvents
import org.lwjgl.glfw.GLFW.glfwSetWindowPos
import org.lwjgl.glfw.GLFW.glfwShowWindow
import org.lwjgl.glfw.GLFW.glfwSwapBuffers
import org.lwjgl.glfw.GLFW.glfwSwapInterval
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowShouldClose
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog
import org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_selectFolderDialog
import org.lwjgl.system.MemoryStack
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// ── 엔트리 포인트 ──────────────────────────────────────────────────────────────

/** tinyfd_openFileDialog 를 vararg String 패턴으로 편리하게 호출하는 래퍼. */
private fun openFileDialog(title: String, defaultPath: String, desc: String, vararg patterns: String): String? =
    MemoryStack.stackPush().use { stack ->
        val pb = stack.mallocPointer(patterns.size)
        patterns.forEach { pb.put(stack.UTF8(it)) }
        pb.flip()
        tinyfd_openFileDialog(title, defaultPath, pb, desc, false)
    }

fun main() {
    GLFWErrorCallback.createPrint(System.err).set()
    check(glfwInit()) { "GLFW 초기화 실패" }

    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)

    val monitor = glfwGetPrimaryMonitor()
    val vidMode = checkNotNull(glfwGetVideoMode(monitor)) { "VideoMode 없음" }
    val winW = 1100; val winH = 750
    val win  = glfwCreateWindow(winW, winH, "StelLane  Song Builder", NULL, NULL)
    check(win != NULL) { "창 생성 실패" }

    glfwSetWindowPos(win, (vidMode.width() - winW) / 2, (vidMode.height() - winH) / 2)
    glfwMakeContextCurrent(win)
    GL.createCapabilities()
    glfwSwapInterval(1)
    glfwShowWindow(win)

    // Dear ImGui 초기화
    ImGui.createContext()
    ImGui.getIO().iniFilename = null
    ImGui.styleColorsDark()
    applyStelLaneTheme()

    val imGuiGlfw = ImGuiImplGlfw()
    val imGuiGl3  = ImGuiImplGl3()
    imGuiGlfw.init(win, true)
    imGuiGl3.init("#version 330 core")

    val app = BuilderApp()

    while (!glfwWindowShouldClose(win)) {
        glfwPollEvents()
        glClearColor(0.04f, 0.02f, 0.12f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)

        imGuiGlfw.newFrame()
        ImGui.newFrame()
        app.render()
        ImGui.render()
        imGuiGl3.renderDrawData(ImGui.getDrawData())

        glfwSwapBuffers(win)
    }

    imGuiGl3.dispose()
    imGuiGlfw.dispose()
    ImGui.destroyContext()
    glfwFreeCallbacks(win)
    glfwDestroyWindow(win)
    glfwTerminate()
}

private fun applyStelLaneTheme() {
    val s = ImGui.getStyle()
    s.windowRounding = 5f
    s.frameRounding  = 3f
    s.grabRounding   = 3f
    s.popupRounding  = 3f

    fun col(idx: Int, r: Float, g: Float, b: Float, a: Float = 1f) = s.setColor(idx, r, g, b, a)
    col(imgui.flag.ImGuiCol.WindowBg,         0.03f, 0.02f, 0.08f, 0.96f)
    col(imgui.flag.ImGuiCol.ChildBg,          0.04f, 0.02f, 0.10f, 0.80f)
    col(imgui.flag.ImGuiCol.PopupBg,          0.06f, 0.04f, 0.16f, 0.95f)
    col(imgui.flag.ImGuiCol.TitleBg,          0.05f, 0.03f, 0.18f, 1.00f)
    col(imgui.flag.ImGuiCol.TitleBgActive,    0.18f, 0.10f, 0.45f, 1.00f)
    col(imgui.flag.ImGuiCol.MenuBarBg,        0.05f, 0.03f, 0.18f, 1.00f)
    col(imgui.flag.ImGuiCol.Header,           0.28f, 0.16f, 0.52f, 0.65f)
    col(imgui.flag.ImGuiCol.HeaderHovered,    0.38f, 0.22f, 0.68f, 0.80f)
    col(imgui.flag.ImGuiCol.HeaderActive,     0.44f, 0.27f, 0.74f, 1.00f)
    col(imgui.flag.ImGuiCol.Button,           0.25f, 0.14f, 0.48f, 0.80f)
    col(imgui.flag.ImGuiCol.ButtonHovered,    0.34f, 0.21f, 0.62f, 1.00f)
    col(imgui.flag.ImGuiCol.ButtonActive,     0.40f, 0.26f, 0.70f, 1.00f)
    col(imgui.flag.ImGuiCol.FrameBg,          0.09f, 0.06f, 0.18f, 0.80f)
    col(imgui.flag.ImGuiCol.FrameBgHovered,   0.14f, 0.09f, 0.26f, 0.85f)
    col(imgui.flag.ImGuiCol.FrameBgActive,    0.19f, 0.13f, 0.34f, 1.00f)
    col(imgui.flag.ImGuiCol.CheckMark,        0.80f, 0.60f, 1.00f, 1.00f)
    col(imgui.flag.ImGuiCol.Separator,        0.22f, 0.16f, 0.40f, 0.80f)
}

// ── 빌더 앱 ──────────────────────────────────────────────────────────────────

/**
 * Dear ImGui 기반 StelLane Song Builder 앱.
 *
 * - description.yml 에서 곡 정보 + YouTube URL 자동 로드
 * - yt-dlp 로 YouTube 영상 다운로드 및 슬러그 이름으로 저장
 * - 커버 이미지 URL 다운로드 또는 로컬 파일 복사
 * - 난이도별 빈 채보 JSON 생성 및 song.json 메타 파일 출력
 */
class BuilderApp {

    // 폼 필드 버퍼
    private val tfTitle    = ImString(256)
    private val tfArtist   = ImString(256)
    private val tfBpm      = ImString(16)
    private val tfDiffs    = ImString(256).apply { set("easy,hard") }
    private val tfVideo    = ImString(512)   // YouTube URL 또는 로컬 경로
    private val tfAudio    = ImString(512)
    private val tfCover    = ImString(512)   // URL 또는 로컬 경로
    private val tfSongsDir = ImString(512)

    // 로그
    private val logLines             = mutableListOf<String>()
    @Volatile private var scrollLog  = false

    // 비동기 작업 상태
    @Volatile private var ytdlpRunning = false
    @Volatile private var buildRunning = false

    init {
        tfSongsDir.set(File(System.getProperty("user.dir"), "songs").absolutePath)
    }

    // ── 렌더링 ────────────────────────────────────────────────────────────────

    fun render() {
        val io = ImGui.getIO()
        ImGui.setNextWindowPos(0f, 0f, ImGuiCond.Always)
        ImGui.setNextWindowSize(io.displaySizeX, io.displaySizeY, ImGuiCond.Always)

        val winFlags = ImGuiWindowFlags.NoTitleBar   or
                       ImGuiWindowFlags.NoResize     or
                       ImGuiWindowFlags.NoMove       or
                       ImGuiWindowFlags.NoScrollbar  or
                       ImGuiWindowFlags.MenuBar

        if (!ImGui.begin("##builder", winFlags)) { ImGui.end(); return }

        if (ImGui.beginMenuBar()) {
            ImGui.textColored(0.51f, 0.39f, 0.78f, 1f, "StelLane  Song Builder")
            ImGui.endMenuBar()
        }

        // 가로 2컬럼: 왼쪽 폼 / 오른쪽 로그
        ImGui.columns(2, "##cols", true)
        val leftW = io.displaySizeX * 0.58f
        ImGui.setColumnWidth(0, leftW)

        renderForm()
        ImGui.nextColumn()
        renderLog()

        ImGui.columns(1)
        ImGui.end()
    }

    // ── 폼 ────────────────────────────────────────────────────────────────────

    private fun renderForm() {
        // ── 곡 정보 ────────────────────────────────────────────────────────────
        if (ImGui.collapsingHeader("곡 정보", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.pushItemWidth(-1f)
            ImGui.inputText("##title",  tfTitle);  ImGui.sameLine(0f, 0f); ImGui.text(" 제목 *")
            ImGui.inputText("##artist", tfArtist); ImGui.sameLine(0f, 0f); ImGui.text(" 아티스트 *")
            ImGui.inputText("##bpm",    tfBpm);    ImGui.sameLine(0f, 0f); ImGui.text(" BPM")
            ImGui.inputText("##diffs",  tfDiffs);  ImGui.sameLine(0f, 0f); ImGui.text(" 난이도 (쉼표 구분) *")
            ImGui.popItemWidth()
        }

        ImGui.spacing()

        // ── description.yml ────────────────────────────────────────────────────
        if (ImGui.collapsingHeader("description.yml", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.textWrapped("title/artist/bpm/difficulties/video/audio/cover 필드를 자동으로 채웁니다.")
            if (ImGui.button("불러오기…##loaddesc")) {
            val path = openFileDialog("description.yml 선택", "", "YAML 파일", "*.yml", "*.yaml")
                if (path != null) loadDescriptionYml(path)
            }
        }

        ImGui.spacing()

        // ── 파일 선택 ──────────────────────────────────────────────────────────
        if (ImGui.collapsingHeader("파일 선택", ImGuiTreeNodeFlags.DefaultOpen)) {

            // 영상
            ImGui.text("영상  (YouTube URL 또는 로컬 파일)")
            ImGui.setNextItemWidth(-130f)
            ImGui.inputText("##video", tfVideo)
            ImGui.sameLine()
            if (ImGui.button("찾기##bv")) {
                val p = openFileDialog("영상 파일 선택", "", "영상 파일", "*.mp4","*.mkv","*.avi","*.mov","*.webm")
                if (p != null) tfVideo.set(p)
            }
            if (isYouTubeUrl(tfVideo.get())) {
                ImGui.sameLine()
                if (ytdlpRunning) {
                    ImGui.textDisabled("⏳ 다운로드 중…")
                } else {
                    if (ImGui.button("yt-dlp 다운로드##ydl")) downloadYouTube()
                }
            }

            ImGui.spacing()

            // 오디오
            ImGui.text("오디오 파일  (없으면 영상 오디오 사용)")
            ImGui.setNextItemWidth(-60f)
            ImGui.inputText("##audio", tfAudio)
            ImGui.sameLine()
            if (ImGui.button("찾기##ba")) {
                val p = openFileDialog("오디오 파일 선택", "", "오디오 파일", "*.mp3","*.ogg","*.flac","*.wav","*.aac")
                if (p != null) tfAudio.set(p)
            }

            ImGui.spacing()

            // 커버 이미지
            ImGui.text("커버 이미지  (URL 또는 로컬 파일)")
            ImGui.setNextItemWidth(-130f)
            ImGui.inputText("##cover", tfCover)
            ImGui.sameLine()
            if (ImGui.button("찾기##bc")) {
                val p = openFileDialog("커버 이미지 선택", "", "이미지 파일", "*.jpg","*.jpeg","*.png","*.webp")
                if (p != null) tfCover.set(p)
            }
            if (isHttpUrl(tfCover.get())) {
                ImGui.sameLine()
                if (ImGui.button("URL 다운로드##dc")) downloadCoverUrl()
            }

            ImGui.spacing()

            // songs 폴더
            ImGui.text("songs 폴더 *")
            ImGui.setNextItemWidth(-60f)
            ImGui.inputText("##songsdir", tfSongsDir)
            ImGui.sameLine()
            if (ImGui.button("찾기##bd")) {
                val d = tinyfd_selectFolderDialog("songs 폴더 선택", tfSongsDir.get())
                if (d != null) tfSongsDir.set(d)
            }
        }

        ImGui.spacing()
        ImGui.separator()
        ImGui.spacing()

        // ── 빌드 버튼 ──────────────────────────────────────────────────────────
        if (buildRunning || ytdlpRunning) {
            ImGui.textDisabled("⏳ 작업 중…  빌드 후 로그를 확인하세요.")
        } else {
            if (ImGui.button("  빌드  (Build Song)  ")) build()
        }
    }

    // ── 로그 ──────────────────────────────────────────────────────────────────

    private fun renderLog() {
        ImGui.text("로그")
        ImGui.separator()
        val avail = ImGui.getContentRegionAvailY()
        if (ImGui.beginChild("##log", 0f, avail, true)) {
            synchronized(logLines) {
                for (line in logLines) {
                    val (r, g, b) = when {
                        line.startsWith("✅") || line.startsWith("📁") -> Triple(0.50f, 1.00f, 0.50f)
                        line.startsWith("❌") || line.startsWith("⚠")  -> Triple(1.00f, 0.40f, 0.40f)
                        line.startsWith("▶")                           -> Triple(0.70f, 0.70f, 1.00f)
                        line.startsWith("─")                           -> Triple(0.35f, 0.28f, 0.55f)
                        else                                           -> Triple(0.85f, 0.85f, 0.90f)
                    }
                    ImGui.textColored(r, g, b, 1f, line)
                }
            }
            if (scrollLog) { ImGui.setScrollHereY(1f); scrollLog = false }
        }
        ImGui.endChild()
    }

    // ── 로그 헬퍼 ─────────────────────────────────────────────────────────────

    private fun log(msg: String) {
        synchronized(logLines) { logLines.add(msg) }
        scrollLog = true
    }

    // ── description.yml 로드 ──────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun loadDescriptionYml(path: String) {
        runCatching {
            val data = Yaml().load<Map<String, Any>>(File(path).reader())
            (data["title"]  as? String)?.let { tfTitle.set(it)  }
            (data["artist"] as? String)?.let { tfArtist.set(it) }
            (data["bpm"]    as? Number)?.let { tfBpm.set(it.toString()) }

            val diffs: String? = when (val d = data["difficulties"]) {
                is List<*> -> d.filterIsInstance<String>().joinToString(",")
                is String  -> d
                else       -> null
            }
            if (!diffs.isNullOrBlank()) tfDiffs.set(diffs)

            // video 필드: YouTube URL 또는 로컬 경로
            val videoDir = File(path).parentFile
            when (val v = data["video"] as? String) {
                null -> Unit
                else -> tfVideo.set(if (isYouTubeUrl(v)) v else File(videoDir, v).absolutePath)
            }
            // audio 필드
            (data["audio"] as? String)?.let { a ->
                tfAudio.set(File(videoDir, a).absolutePath)
            }
            // cover 필드: URL 또는 로컬 경로
            when (val c = data["cover"] as? String) {
                null -> Unit
                else -> tfCover.set(if (isHttpUrl(c)) c else File(videoDir, c).absolutePath)
            }

            log("✅ description.yml 로드: $path")
        }.onFailure { e -> log("❌ YML 로드 실패: ${e.message}") }
    }

    // ── YouTube 다운로드 (yt-dlp) ─────────────────────────────────────────────

    private fun downloadYouTube() {
        if (ytdlpRunning) return
        val url  = tfVideo.get().trim()
        val slug = makeSlug(tfTitle.get().trim()).ifEmpty { "video" }
        val dir  = File(tfSongsDir.get().trim()).also { if (!it.exists()) it.mkdirs() }
        val songDir = File(dir, slug.ifEmpty { "song" }).also { it.mkdirs() }
        val outTemplate = File(songDir, "$slug.%(ext)s").absolutePath

        ytdlpRunning = true
        Thread {
            try {
                log("▶ yt-dlp 다운로드 시작")
                log("  URL: $url")
                val pb = ProcessBuilder(
                    "yt-dlp", "--no-playlist",
                    "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                    "--merge-output-format", "mp4",
                    "-o", outTemplate,
                    url
                ).also { it.redirectErrorStream(true) }
                val proc = pb.start()
                proc.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line -> log(line) }
                }
                val exit = proc.waitFor()
                if (exit == 0) {
                    val resultFile = File(songDir, "$slug.mp4")
                    if (resultFile.exists()) tfVideo.set(resultFile.absolutePath)
                    log("✅ 다운로드 완료: ${resultFile.absolutePath}")
                } else {
                    log("❌ yt-dlp 종료 코드: $exit")
                }
            } catch (e: Exception) {
                log("❌ yt-dlp 실행 실패: ${e.message}")
                log("   yt-dlp 가 PATH 에 있는지 확인하세요.")
            } finally {
                ytdlpRunning = false
            }
        }.also { it.isDaemon = true }.start()
    }

    // ── 커버 이미지 URL 다운로드 ─────────────────────────────────────────────

    private fun downloadCoverUrl() {
        val url  = tfCover.get().trim()
        val slug = makeSlug(tfTitle.get().trim()).ifEmpty { "cover" }
        val ext  = url.substringAfterLast('.').substringBefore('?').lowercase()
            .let { if (it in setOf("jpg","jpeg","png","webp")) it else "jpg" }
        val dir  = File(tfSongsDir.get().trim(), slug).also { it.mkdirs() }
        val dest = File(dir, "${slug}_cover.$ext")

        Thread {
            try {
                log("▶ 커버 이미지 다운로드: $url")
                URL(url).openStream().use { inp ->
                    dest.outputStream().use { out -> inp.copyTo(out) }
                }
                tfCover.set(dest.absolutePath)
                log("✅ 커버 저장: ${dest.absolutePath}")
            } catch (e: Exception) {
                log("❌ 커버 다운로드 실패: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
    }

    // ── 빌드 ─────────────────────────────────────────────────────────────────

    private fun build() {
        val title  = tfTitle.get().trim()
        val artist = tfArtist.get().trim()
        val diffs  = tfDiffs.get().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val songsDir = File(tfSongsDir.get().trim())

        if (title.isEmpty() || artist.isEmpty() || diffs.isEmpty()) {
            log("❌ 제목, 아티스트, 난이도를 입력하세요.")
            return
        }
        if (!songsDir.exists() && !songsDir.mkdirs()) {
            log("❌ songs 폴더를 생성할 수 없습니다: ${songsDir.absolutePath}"); return
        }

        buildRunning = true
        Thread {
            try {
                val slug    = makeSlug(title)
                val songDir = File(songsDir, slug).also { it.mkdirs() }
                log("📁 곡 폴더: ${songDir.absolutePath}")

                fun copyOrSkip(srcPath: String, destName: String): String? {
                    if (srcPath.isBlank()) return null
                    val src = File(srcPath)
                    if (!src.exists()) { log("⚠ 파일 없음: $srcPath"); return null }
                    val dest = File(songDir, destName)
                    Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    log("  복사: ${src.name} → ${dest.name}")
                    return dest.name
                }

                // 영상 — YouTube URL 이면 이미 downloadYouTube() 로 처리됨, 아니면 복사
                val videoSrc = tfVideo.get().trim()
                val videoName: String? = if (isYouTubeUrl(videoSrc)) {
                    val f = File(songDir, "$slug.mp4")
                    if (f.exists()) f.name else null
                } else {
                    val ext = File(videoSrc).extension.let { if (it.isBlank()) "mp4" else it }
                    copyOrSkip(videoSrc, "$slug.$ext")
                }

                // 오디오
                val audioSrc  = tfAudio.get().trim()
                val audioName: String? = if (audioSrc.isNotBlank()) {
                    val ext = File(audioSrc).extension.let { if (it.isBlank()) "mp3" else it }
                    copyOrSkip(audioSrc, "$slug.$ext")
                } else null

                // 커버 — URL 이면 이미 downloadCoverUrl() 로 처리됨
                val coverSrc = tfCover.get().trim()
                val coverName: String? = if (isHttpUrl(coverSrc)) {
                    // URL 다운로드는 별도 버튼으로 처리, 빌드 시점에는 파일이 존재해야 함
                    val pattern = songDir.listFiles()
                        ?.firstOrNull { it.nameWithoutExtension == "${slug}_cover" }
                    pattern?.name
                } else {
                    val ext = File(coverSrc).extension.let { if (it.isBlank()) "jpg" else it }
                    copyOrSkip(coverSrc, "${slug}_cover.$ext")
                }

                // 난이도별 빈 채보 JSON 생성
                val diffMap = mutableMapOf<String, String>()
                for (diff in diffs) {
                    val chartFile = File(songDir, "${diff.lowercase()}.json")
                    if (!chartFile.exists()) {
                        ChartParser.serializeChart(Chart(offsetMs = 0L, notes = emptyList()), chartFile)
                        log("  채보 생성: ${chartFile.name}")
                    } else {
                        log("  채보 유지 (기존): ${chartFile.name}")
                    }
                    diffMap[diff] = chartFile.name
                }

                // Song 메타 JSON
                val song = Song(
                    title          = title,
                    artist         = artist,
                    bpm            = tfBpm.get().trim().toIntOrNull(),
                    coverImagePath = coverName,
                    videoPath      = videoName,
                    audioPath      = audioName,
                    difficulties   = diffMap
                )
                val metaFile = File(songsDir, "$slug.json")
                com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(metaFile, song)
                log("✅ 완료: ${metaFile.absolutePath}")
                log("─────────────────────────────────────────")
            } catch (e: Exception) {
                log("❌ 빌드 실패: ${e.message}")
            } finally {
                buildRunning = false
            }
        }.also { it.isDaemon = true }.start()
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private fun makeSlug(title: String): String =
        title.replace(Regex("[^\\w가-힣ㄱ-ㅎㅏ-ㅣ\\-_. ]"), "_").trim()

    private fun isYouTubeUrl(s: String): Boolean =
        s.contains("youtube.com/watch") || s.contains("youtu.be/")

    private fun isHttpUrl(s: String): Boolean =
        s.startsWith("http://") || s.startsWith("https://")
}

