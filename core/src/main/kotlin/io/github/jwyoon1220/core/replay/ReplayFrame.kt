package io.github.jwyoon1220.core.replay

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 게임 한 프레임의 스냅샷 — 골든 리플레이 기준점.
 *
 * 프레임별로 입력과 결과(판정, 점수, 콤보)를 기록해
 * 나중에 ECS 구현과의 동등성을 프레임 단위로 검증합니다.
 */
data class ReplayFrame(
    /** 누적 프레임 번호 (0부터 시작) */
    @JsonProperty("frame")
    val frameNum: Int,

    /** 현재 게임 시간 (ms) */
    @JsonProperty("game_time_ms")
    val gameTimeMs: Long,

    /** 입력된 레인 (0–3, 또는 -1 = 입력 없음). */
    @JsonProperty("lane_pressed")
    val lanePressed: List<Int> = emptyList(),

    /** 입력된 레인 (RELEASE). */
    @JsonProperty("lane_released")
    val laneReleased: List<Int> = emptyList(),

    // ── 게임 상태 (프레임 종료 시점의 누적 값) ──────────────────────────────

    /** 현재 점수 */
    @JsonProperty("score")
    val score: Int = 0,

    /** 현재 콤보 */
    @JsonProperty("combo")
    val combo: Int = 0,

    /** MAX 콤보 (누적) */
    @JsonProperty("max_combo")
    val maxCombo: Int = 0,

    /** 판정 카운트: [PERFECT, GREAT, GOOD, MISS] */
    @JsonProperty("judgment_counts")
    val judgmentCounts: List<Int> = listOf(0, 0, 0, 0),

    /** 이번 프레임에 발생한 판정 (있으면, 예: "PERFECT", 없으면 "") */
    @JsonProperty("judgment_this_frame")
    val judgmentThisFrame: String = "",

    /** 화면의 활성 노트 인덱스 집합 (판정 대기 중인 노트들) */
    @JsonProperty("active_note_indices")
    val activeNoteIndices: List<Int> = emptyList()
)

/**
 * 전체 리플레이 파일 구조.
 */
data class ReplayFile(
    /** 차트 정보 (재현성 확인용) */
    @JsonProperty("chart_id")
    val chartId: String,

    @JsonProperty("offset_ms")
    val offsetMs: Long,

    @JsonProperty("total_notes")
    val totalNotes: Int,

    @JsonProperty("bpm")
    val bpm: Float,

    /** 프레임 레이트 */
    @JsonProperty("target_fps")
    val targetFps: Int = 60,

    /** 리플레이 프레임 배열 */
    @JsonProperty("frames")
    val frames: List<ReplayFrame> = emptyList()
)
