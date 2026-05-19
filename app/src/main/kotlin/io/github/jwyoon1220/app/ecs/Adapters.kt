package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.Note
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.ecs.Entity

/**
 * 레거시 Chart/Note 데이터 → ECS 엔티티로 변환.
 *
 * 마이그레이션 중 PlayState의 기존 데이터 구조를
 * ECS 세계로 변환하는 어댑터.
 */
object ChartAdapter {
    /**
     * Chart + 개별 Note들을 ECS 엔티티로 생성.
     *
     * @param world 타겟 ECS 세계
     * @param chart 차트 정보
     * @param notes 노트 배열
     * @param bpm BPM (Chart에 없으므로 외부 제공)
     * @return 차트 엔티티 ID (메타데이터 저장용)
     */
    fun createChartEntities(
        world: World,
        chart: Chart,
        notes: List<Note>,
        bpm: Float
    ): Entity {
        // 차트 메타데이터 엔티티
        val chartEntity = world.create()
        world.set(chartEntity, ChartComponent(
            totalNotes = notes.size,
            bpm = bpm,
            offsetMs = chart.offsetMs
        ))

        // 스코어 싱글톤 엔티티
        val scoreEntity = world.create()
        world.set(scoreEntity, ScoreComponent(
            score = 0,
            combo = 0,
            maxCombo = 0,
            judgmentCounts = intArrayOf(0, 0, 0, 0)
        ))

        // 노트 엔티티 생성 (모두 PENDING으로 시작)
        for (note in notes) {
            val noteEntity = world.create()
            world.set(noteEntity, NoteComponent(
                lane = note.lane,
                timeMs = note.time,
                endMs = note.endTime ?: note.time,
                type = note.type,
                state = NoteState.PENDING
            ))
        }

        return chartEntity
    }
}
