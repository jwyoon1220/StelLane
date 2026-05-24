package io.github.jwyoon1220.app

/** 게임 내 전역 상수 보관 객체 */
object Const {
    /** 게임 버전 정보 */
    const val VERSION = "R1.0.2"

    /** 리듬게임 기본 레인 설정 */
    const val LANE_COUNT = 4
    const val LANE_WIDTH = 100
    const val TOTAL_WIDTH = LANE_COUNT * LANE_WIDTH   // 400px

    /** 판정선 비율 및 속도 관련 설정 */
    const val HIT_LINE_RATIO = 0.85f
    const val SCROLL_SPEED = 700f                      // px/s
    const val SPAWN_AHEAD_MS = 1200L                   // 스폰 선행 시간 (ms)
    const val JUDGE_FADE_MS = 600L                     // 판정 텍스트 페이드아웃 (ms)

    /** 오프닝 대기 연출 시간 상세 */
    const val READY_DURATION_MS = 6000.0              // 총 READY 대기 시간 (ms)
    const val INFO_DURATION_MS = 1500.0               // 곡 정보가 완전히 노출되는 시간 (ms)
    const val TRANSITION_DURATION_MS = 500.0          // 곡 정보 페이드아웃 및 레인 페이드인 트랜지션 시간 (ms)
}
