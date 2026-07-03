package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.editor.comp.*
import io.github.jwyoon1220.editor.render.*
import io.github.jwyoon1220.editor.sys.*
import io.github.jwyoon1220.app.editor.sys.EditorInputSystem
import io.github.jwyoon1220.app.resolveMediaPath
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.Decoration
import io.github.jwyoon1220.core.data.MutableChart
import io.github.jwyoon1220.core.data.MutableNote
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.song.DecorationParser
import io.github.jwyoon1220.engine.OpenGLRenderable
import io.github.jwyoon1220.engine.GlEffectProvider
import io.github.jwyoon1220.engine.GlQuadBatchRenderer
import io.github.jwyoon1220.engine.GlScreenEffectData
import io.github.jwyoon1220.engine.ImGuiRenderable
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.render.RenderColor
import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImFloat
import imgui.type.ImInt
import imgui.type.ImString
import java.io.File

class EditorScene(
    private val ctx: GameContext,
    val songEntry: SongEntry,
    private val chartFile: File,
    chart: Chart,
) : Scene(), OpenGLRenderable, ImGuiRenderable, GlEffectProvider {

    override val rendersBackground = true
    override val useOpenGLRenderer: Boolean = true

    // ── 공유 뮤터블 상태 (시스템들이 생성자 주입으로 공유) ──────────────────────

    private val mutableChart = MutableChart(
        offsetMs = chart.offsetMs,
        notes    = chart.notes.map { MutableNote(it.time, it.lane, it.type, it.endTime) }.toMutableList()
    )
    private val notesLock = Any()
    private val bpm = songEntry.song.bpm?.toDouble()

    // 편집기 ECS 엔티티
    private var editorEntity: Long = 0L

    // ImGui 상태 (renderImGui에서만 접근)
    private val imId       = ImString(128)
    private val imImage    = ImString(256)
    private val imTime     = ImInt(0)
    private val imDuration = ImInt(0)
    private val imX        = ImFloat(0f)
    private val imY        = ImFloat(0f)
    private val imW        = ImFloat(0f)
    private val imH        = ImFloat(0f)
    private val imOpacity  = ImFloat(1f)
    private val imRotation = ImFloat(0f)
    private val imDepth    = ImInt(0)

    private var imageBrowserOpen  = false
    private var imageBrowserDir   = songEntry.songDir
    private var imageBrowserSel: File? = null
    private var imageBrowserEntries: List<ImageBrowserEntry> = emptyList()

    private data class ImageBrowserEntry(val file: File, val label: String, val isDir: Boolean)

    private companion object {
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp")
    }

    // ── GL 효과 / 커스텀 렌더 ───────────────────────────────────────────────

    override fun collectActiveGlEffects(): List<GlScreenEffectData> {
        val decComp = if (editorEntity == 0L) null else world.get<DecorationRendererComp>(editorEntity)
        val t       = if (editorEntity == 0L) 0L else world.get<PlaybackComp>(editorEntity)?.currentTimeMs ?: 0L
        return decComp?.renderer?.collectGlEffects(t) ?: emptyList()
    }

    override fun renderOpenGL(renderer: GlQuadBatchRenderer) {
        val texId = ctx.videoBackground.getGlTextureId()
        if (texId == 0 || editorEntity == 0L) return
        val layout = world.get<LayoutComp>(editorEntity) ?: return
        if (layout.vpW <= 0 || layout.vpH <= 0) return
        renderer.drawRect(
            layout.vpX.toFloat(), layout.vpY.toFloat(),
            layout.vpW.toFloat(), layout.vpH.toFloat(),
            RenderColor.WHITE, texId
        )
    }

    // ── 씬 수명 주기 ──────────────────────────────────────────────────────────

    override fun enter() {
        super.enter()

        val initialDecorData = DecorationParser.parseOrNull(songEntry.songDir) ?: io.github.jwyoon1220.core.data.DecorationData()

        // 엔티티 생성 및 컴포넌트 부착
        editorEntity = world.create()
        val layout = LayoutComp()
        layout.recalcViewport()

        world.set(editorEntity, EditorStateComp())
        world.set(editorEntity, PlaybackComp())
        world.set(editorEntity, TimelineViewComp())
        world.set(editorEntity, QuantizeComp())
        world.set(editorEntity, SelectionComp())
        world.set(editorEntity, ClipboardComp())
        world.set(editorEntity, UndoRedoComp())
        world.set(editorEntity, RecordingComp())
        world.set(editorEntity, DragStateComp())
        world.set(editorEntity, DecorationSelectionComp())
        world.set(editorEntity, layout)
        world.set(editorEntity, ContextMenuComp())
        world.set(editorEntity, SeekComp())
        world.set(editorEntity, KeyModComp())
        world.set(editorEntity, OverviewComp())
        world.set(editorEntity, DecorationRendererComp(
            decorations  = initialDecorData.decorations.toMutableList(),
            songDirPath  = songEntry.songDir.absolutePath
        ).also { it.rebuild() })

        // 시스템 등록 (update 순서 중요)
        register(
            PlaybackSystem(ctx.videoBackground, editorEntity, mutableChart.offsetMs),
            RecordingSystem(editorEntity, mutableChart, notesLock, bpm),
            SeekSystem(ctx.videoBackground, editorEntity),
            EditorInputSystem(ctx, editorEntity, mutableChart, notesLock, bpm,
                songEntry.songDir, chartFile, mutableChart.offsetMs),
            // 렌더 시스템 (produce 호출 순서 = 레이어 순서)
            BackgroundRenderSystem(editorEntity),
            TransportBarRenderSystem(editorEntity),
            LeftPanelRenderSystem(editorEntity),
            ProgramMonitorRenderSystem(editorEntity),
            TimelineRenderSystem(editorEntity, mutableChart, notesLock, bpm, mutableChart.offsetMs),
            OverviewBarRenderSystem(editorEntity, mutableChart, notesLock, mutableChart.offsetMs, ctx.videoBackground),
        )

        // 비디오 재생 시작
        val mediaPath = songEntry.resolveMediaPath()
        if (mediaPath != null) {
            ctx.videoBackground.onPlayingStarted = {
                world.get<PlaybackComp>(editorEntity)?.isPlaying = true
            }
            ctx.videoBackground.play(mediaPath)
        }

        ctx.inputManager.clearEvents()
    }

    override fun exit() {
        ctx.videoBackground.onPlayingStarted = null
        ctx.videoBackground.stop()
        ctx.inputManager.clearEvents()
        editorEntity = 0L
        super.exit()
    }

    // ── ImGui ──────────────────────────────────────────────────────────────────

    override fun renderImGui() {
        if (editorEntity == 0L) return
        val ctx2 = world.require<ContextMenuComp>(editorEntity)
        val ds   = world.require<DecorationSelectionComp>(editorEntity)
        val dec  = world.require<DecorationRendererComp>(editorEntity)
        val st   = world.require<EditorStateComp>(editorEntity)

        // 컨텍스트 메뉴
        if (ctx2.pending) { ImGui.openPopup("EditorCtxMenu"); ctx2.pending = false }
        if (ImGui.beginPopup("EditorCtxMenu")) {
            val ur = world.require<UndoRedoComp>(editorEntity)
            if (ctx2.noteIdx >= 0) {
                if (ImGui.menuItem("노트 삭제")) {
                    ur.undoStack.addLast(synchronized(notesLock) { mutableChart.notes.map { it.copy() } })
                    ur.redoStack.clear()
                    synchronized(notesLock) { mutableChart.notes.removeAt(ctx2.noteIdx) }
                    world.require<SelectionComp>(editorEntity).selectedIndices.clear()
                    st.unsaved = true
                }
            } else if (ctx2.decorIdx >= 0) {
                if (ImGui.menuItem("장식 편집")) { ds.editingIdx = ctx2.decorIdx; ds.editingIsNew = false; loadDecorToImGui(dec.decorations.getOrNull(ctx2.decorIdx)) }
                if (ImGui.menuItem("장식 삭제")) { dec.decorations.removeAt(ctx2.decorIdx); dec.rebuild(); ds.selectedIdx = -1; st.unsaved = true }
            } else {
                if (st.mode == EditorMode.DECOR) {
                    if (ImGui.menuItem("여기에 장식 추가")) {
                        ds.editingIdx = -2; ds.editingIsNew = true
                        val newDec = Decoration(id = "new_decor", timeMs = ctx2.clickMs, durationMs = 1000L)
                        loadDecorToImGui(newDec)
                    }
                } else {
                    val laneChar = arrayOf("D", "F", "J", "K")[ctx2.lane]
                    if (ImGui.menuItem("$laneChar 레인에 노트 추가")) {
                        val snap = synchronized(notesLock) { mutableChart.notes.map { it.copy() } }
                        ur.undoStack.addLast(snap); ur.redoStack.clear()
                        synchronized(notesLock) {
                            mutableChart.notes.add(MutableNote(ctx2.clickMs, ctx2.lane,
                                if (st.noteInputMode == NoteInputMode.NORMAL) io.github.jwyoon1220.core.data.NoteType.SHORT
                                else io.github.jwyoon1220.core.data.NoteType.LONG,
                                if (st.noteInputMode == NoteInputMode.NORMAL) null else ctx2.clickMs + 500))
                            mutableChart.notes.sortBy { it.time }
                        }
                        st.unsaved = true
                    }
                }
            }
            ImGui.separator()
            if (ImGui.menuItem("취소")) ImGui.closeCurrentPopup()
            ImGui.endPopup()
        }

        // 장식 편집 다이얼로그
        if (ds.editingIdx != -1) {
            ImGui.setNextWindowSize(420f, 530f)
            if (ImGui.begin("Decoration Editor", ImGuiWindowFlags.NoCollapse)) {
                ImGui.inputText("ID", imId)
                ImGui.inputText("Image", imImage)
                if (ImGui.button("파일 선택...")) openImageBrowser()
                ImGui.separator()
                ImGui.inputInt("Time (ms)", imTime); ImGui.inputInt("Duration (ms)", imDuration)
                ImGui.separator()
                ImGui.sliderFloat("X", imX.data, 0f, 1f); ImGui.sliderFloat("Y", imY.data, 0f, 1f)
                ImGui.inputFloat("Width", imW); ImGui.inputFloat("Height", imH)
                ImGui.separator()
                ImGui.sliderFloat("Opacity", imOpacity.data, 0f, 1f)
                ImGui.dragFloat("Rotation", imRotation.data, 0.5f, -360f, 360f)
                ImGui.inputInt("Depth", imDepth)
                if (ImGui.button("확인", 120f, 30f)) {
                    val updated = Decoration(
                        id = imId.get(), image = imImage.get(),
                        timeMs = imTime.get().toLong(), durationMs = imDuration.get().toLong(),
                        x = imX.get(), y = imY.get(), width = imW.get(), height = imH.get(),
                        opacity = imOpacity.get(), rotation = imRotation.get(), depth = imDepth.get(),
                        effects = if (ds.editingIsNew) emptyList() else dec.decorations.getOrNull(ds.editingIdx)?.effects ?: emptyList()
                    )
                    if (ds.editingIsNew || ds.editingIdx == -2) {
                        dec.decorations.add(updated); dec.decorations.sortBy { it.timeMs }
                    } else {
                        dec.decorations[ds.editingIdx] = updated
                    }
                    dec.save(); st.unsaved = true; ds.editingIdx = -1; closeImageBrowser()
                }
                ImGui.sameLine()
                if (ImGui.button("취소", 120f, 30f)) { ds.editingIdx = -1; closeImageBrowser() }
                ImGui.end()
            }
        }

        renderImageBrowserImGui()
    }

    // ── ImGui 이미지 브라우저 ──────────────────────────────────────────────────

    private fun openImageBrowser() {
        imageBrowserDir = songEntry.songDir
        imageBrowserSel = null
        refreshBrowserEntries()
        imageBrowserOpen = true
    }

    private fun closeImageBrowser() { imageBrowserOpen = false; imageBrowserSel = null }

    private fun refreshBrowserEntries() {
        val dir = imageBrowserDir.takeIf { it.isDirectory } ?: songEntry.songDir
        imageBrowserDir = dir
        imageBrowserEntries = dir.listFiles().orEmpty()
            .filter { it.isDirectory || it.extension.lowercase() in IMAGE_EXTS }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { ImageBrowserEntry(it, if (it.isDirectory) "[DIR] ${it.name}" else it.name, it.isDirectory) }
    }

    private fun renderImageBrowserImGui() {
        if (!imageBrowserOpen) return
        ImGui.setNextWindowSize(560f, 420f)
        if (ImGui.begin("Image Browser", ImGuiWindowFlags.NoCollapse)) {
            ImGui.textWrapped(imageBrowserDir.absolutePath)
            if (ImGui.button("상위 폴더")) { imageBrowserDir.parentFile?.takeIf { it.isDirectory }?.let { imageBrowserDir = it; refreshBrowserEntries() } }
            ImGui.sameLine()
            if (ImGui.button("곡 폴더")) { imageBrowserDir = songEntry.songDir; refreshBrowserEntries() }
            ImGui.sameLine()
            if (ImGui.button("새로고침")) refreshBrowserEntries()
            ImGui.separator()
            ImGui.beginChild("BrowserList", 0f, 270f, true)
            for (entry in imageBrowserEntries) {
                val isSel = imageBrowserSel?.absolutePath == entry.file.absolutePath
                if (ImGui.selectable(entry.label, isSel)) {
                    if (entry.isDir) { imageBrowserDir = entry.file; refreshBrowserEntries() } else imageBrowserSel = entry.file
                }
            }
            ImGui.endChild()
            val selLabel = imageBrowserSel?.let {
                runCatching { it.toRelativeString(songEntry.songDir).replace("\\", "/") }.getOrDefault(it.absolutePath)
            } ?: "선택 없음"
            ImGui.textWrapped(selLabel)
            if (ImGui.button("선택", 120f, 30f)) {
                imageBrowserSel?.let { file ->
                    imImage.set(runCatching { file.toRelativeString(songEntry.songDir).replace("\\", "/") }.getOrDefault(file.absolutePath))
                    closeImageBrowser()
                }
            }
            ImGui.sameLine()
            if (ImGui.button("취소", 120f, 30f)) closeImageBrowser()
            ImGui.end()
        }
    }

    private fun loadDecorToImGui(d: Decoration?) {
        val dec = d ?: Decoration(id = "new_decor", timeMs = 0L, durationMs = 1000L)
        imId.set(dec.id); imImage.set(dec.image)
        imTime.set(dec.timeMs.toInt()); imDuration.set(dec.durationMs.toInt())
        imX.set(dec.x); imY.set(dec.y); imW.set(dec.width); imH.set(dec.height)
        imOpacity.set(dec.opacity); imRotation.set(dec.rotation); imDepth.set(dec.depth)
    }
}
