package io.github.jwyoon1220.app.editor.comp

import io.github.jwyoon1220.core.data.Decoration
import io.github.jwyoon1220.core.data.MutableNote
import io.github.jwyoon1220.engine.ecs.Component
import it.unimi.dsi.fastutil.ints.IntArraySet

// ── 편집 모드 / 도구 ─────────────────────────────────────────────────────────

enum class EditorMode { NOTE, DECOR }
enum class EditorTool { SELECT, RAZOR }
enum class NoteInputMode { NORMAL, LONG }

data class EditorStateComp(
    var mode: EditorMode = EditorMode.NOTE,
    var tool: EditorTool = EditorTool.SELECT,
    var noteInputMode: NoteInputMode = NoteInputMode.NORMAL,
    var unsaved: Boolean = false,
) : Component

// ── 재생 ─────────────────────────────────────────────────────────────────────

class PlaybackComp(
    @Volatile var isPlaying: Boolean = false,
    var isRecording: Boolean = false,
    @Volatile var currentTimeMs: Long = 0L,
    var lastCacheFrameId: Long = -1L,
) : Component

// ── 타임라인 뷰 ───────────────────────────────────────────────────────────────

class TimelineViewComp(
    var scrollMs: Long = 0L,
    var visibleMs: Long = 6_000L,
    var zoomIdx: Int = 2,
) : Component {
    companion object {
        val ZOOM_LEVELS = longArrayOf(2_000L, 4_000L, 6_000L, 10_000L, 16_000L, 30_000L)
    }
    fun zoomIn()  { if (zoomIdx > 0)                     { zoomIdx--; visibleMs = ZOOM_LEVELS[zoomIdx] } }
    fun zoomOut() { if (zoomIdx < ZOOM_LEVELS.lastIndex) { zoomIdx++; visibleMs = ZOOM_LEVELS[zoomIdx] } }
}

// ── 퀀타이즈 ─────────────────────────────────────────────────────────────────

data class QuantizeComp(
    var enabled: Boolean = true,
    var division: Int = 4,
) : Component

// ── 선택 ─────────────────────────────────────────────────────────────────────

class SelectionComp(
    val selectedIndices: IntArraySet = IntArraySet(),
    var cursorIdx: Int = -1,
) : Component

// ── 클립보드 ──────────────────────────────────────────────────────────────────

data class ClipboardComp(
    var notes: List<MutableNote> = emptyList(),
    var baseMs: Long = 0L,
) : Component

// ── 실행 취소 / 재실행 ────────────────────────────────────────────────────────

class UndoRedoComp(
    val undoStack: ArrayDeque<List<MutableNote>> = ArrayDeque(),
    val redoStack: ArrayDeque<List<MutableNote>> = ArrayDeque(),
    val maxHistory: Int = 50,
) : Component

// ── 녹음 레인 홀드 ────────────────────────────────────────────────────────────

class RecordingComp(
    val heldLaneStartMs: LongArray = LongArray(4) { -1L },
) : Component

// ── 드래그 / 리사이즈 상태 ──────────────────────────────────────────────────

enum class DragType {
    NONE, NOTE_RESIZE,
    DECOR_VP_MOVE, DECOR_VP_RESIZE,
    DECOR_TL_MOVE,
    OVERVIEW_SEEK, TIMELINE_SEEK
}

class DragStateComp(
    var type: DragType = DragType.NONE,
    var noteIdx: Int = -1,
    var decorIdx: Int = -1,
    var timelineDragStartX: Int = -1,
    var decorDragOffsetX: Float = 0f,
    var decorDragOffsetY: Float = 0f,
    var decorResizePressX: Int = 0,
    var decorResizePressY: Int = 0,
    var decorResizeOrigW: Float = 0f,
    var decorResizeOrigH: Float = 0f,
    var noteResizeMoved: Boolean = false,
) : Component

// ── 장식 선택 ─────────────────────────────────────────────────────────────────

class DecorationSelectionComp(
    var selectedIdx: Int = -1,
    var editingIdx: Int = -1,   // -2 = 새로 추가
    var editingIsNew: Boolean = false,
) : Component

// ── 레이아웃 (매 프레임 시스템이 계산) ───────────────────────────────────────

class LayoutComp : Component {
    // 설계 해상도 기반 (1280×720)
    val designW: Int = 1280
    val designH: Int = 720

    val headerH: Int = 50
    val leftPanelW: Int = 260
    val trackHeaderW: Int = 52
    val overviewBarH: Int = 20

    // 콘텐츠 영역 (헤더 아래, 타임라인 위)
    val contentH: Int get() = designH - headerH - timelineAreaH - overviewBarH

    // 타임라인 영역
    val noteLaneH: Int = 50      // 레인 하나 높이
    val decorTlH: Int  = 50      // 장식 트랙 높이
    val rulerH: Int    = 20      // 눈금자 높이
    val timelineAreaH: Int get() = rulerH + noteLaneH * 4 + decorTlH

    // 계산된 좌표
    val contentY: Int get() = headerH
    val tlAreaY:  Int get() = headerH + contentH
    val rulerY:   Int get() = tlAreaY
    val trackY:   Int get() = tlAreaY + rulerH
    val overviewY: Int get() = designH - overviewBarH

    val tlX: Int get() = trackHeaderW
    val tlW: Int get() = designW - trackHeaderW

    fun laneY(lane: Int) = trackY + lane * noteLaneH
    val decorTrackY: Int get() = trackY + noteLaneH * 4

    // 비디오 뷰포트 (콘텐츠 영역 내, 좌측 패널 오른쪽에 중앙 정렬)
    var vpX: Int = 0; var vpY: Int = 0; var vpW: Int = 0; var vpH: Int = 0

    fun recalcViewport() {
        val areaW = designW - leftPanelW
        val areaH = contentH
        val h = (areaH - 10).coerceAtLeast(1)
        val w = (h * 16 / 9).coerceAtMost(areaW - 10)
        val fh = w * 9 / 16
        vpW = w; vpH = fh
        vpX = leftPanelW + (areaW - w) / 2
        vpY = contentY + (areaH - fh) / 2
    }
}

// ── 컨텍스트 메뉴 ─────────────────────────────────────────────────────────────

class ContextMenuComp(
    var pending: Boolean = false,
    var noteIdx: Int = -1,
    var decorIdx: Int = -1,
    var clickMs: Long = 0L,
    var lane: Int = 0,
) : Component

// ── 탐색 (씩 처리) ───────────────────────────────────────────────────────────

class SeekComp(
    var pendingSeekMs: Long = -1L,
    var lastSeekTimeMs: Long = 0L,
) : Component

// ── 키 수정자 ─────────────────────────────────────────────────────────────────

data class KeyModComp(var shiftHeld: Boolean = false) : Component

// ── 오버뷰 바 캐시 ────────────────────────────────────────────────────────────

class OverviewComp(
    var dragActive: Boolean = false,
    var cachedNoteCount: Int = -1,
    var cachedWidth: Int = -1,
    var cachedRangeMs: Long = -1L,
    var overviewImg: java.awt.image.BufferedImage? = null,
) : Component

// ── 장식 렌더러 홀더 (컴포넌트 — 시스템 간 공유) ────────────────────────────

class DecorationRendererComp(
    var decorations: MutableList<Decoration>,
    var songDirPath: String,
) : Component {
    @Volatile var renderer: io.github.jwyoon1220.app.DecorationRenderer? = null
    @Volatile var decorData: io.github.jwyoon1220.core.data.DecorationData =
        io.github.jwyoon1220.core.data.DecorationData(decorations)

    fun rebuild() {
        decorData = io.github.jwyoon1220.core.data.DecorationData(decorations, decorData.screenEffects)
        renderer = io.github.jwyoon1220.app.DecorationRenderer(decorData, java.io.File(songDirPath))
    }

    fun save() {
        rebuild()
        runCatching {
            io.github.jwyoon1220.core.song.DecorationParser.serialize(decorData, java.io.File(songDirPath))
        }
    }
}
