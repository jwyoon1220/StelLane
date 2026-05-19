package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.engine.ecs.EcsSystem
import io.github.jwyoon1220.engine.ecs.MainThreadSystem
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.core.judgment.Judgment
import io.github.jwyoon1220.core.scoring.ScoreEngine

/**
 * 점수 시스템.
 *
 * JudgmentResultComponent를 읽고 ScoreComponent를 업데이트합니다.
 * 또한 레거시 ScoreEngine도 동시에 업데이트해 호환성을 유지합니다.
 */
class ScoreSystem(
    private val legacyScoreEngine: ScoreEngine
) : EcsSystem, MainThreadSystem {

    override fun update(world: World, input: InputSnapshot, deltaTime: Double) {
        // 모든 판정 결과 엔티티 조회
        val judgmentResults = world.entitiesWith<JudgmentResultComponent>()

        // ScoreComponent 찾기 (싱글톤)
        var scoreEntity = 0L
        var scoreComp: ScoreComponent? = null
        for (entity in world.entitiesWith<ScoreComponent>()) {
            scoreEntity = entity
            scoreComp = world.get<ScoreComponent>(entity)
            break
        }

        if (scoreComp == null) return

        // 각 판정 결과 처리
        var newScore = scoreComp.score
        var newCombo = scoreComp.combo
        var newMaxCombo = scoreComp.maxCombo
        val newCounts = scoreComp.judgmentCounts.copyOf()

        for (entity in judgmentResults) {
            val result = world.get<JudgmentResultComponent>(entity) ?: continue

            // 레거시 엔진도 업데이트 (호환성)
            legacyScoreEngine.onJudgment(result.judgment)

            // 점수 계산 (레거시 로직)
            val points = when (result.judgment) {
                Judgment.PERFECT -> 100
                Judgment.GREAT   -> 50
                Judgment.GOOD    -> 20
                Judgment.MISS    -> 0
            }
            newScore += points

            // 콤보 업데이트
            newCombo = if (result.judgment != Judgment.MISS) newCombo + 1 else 0
            newMaxCombo = maxOf(newMaxCombo, newCombo)

            // 판정 카운트
            val index = result.judgment.ordinal
            newCounts[index]++

            // 판정 결과 엔티티 제거
            world.destroy(entity)
        }

        // ScoreComponent 업데이트
        if (scoreComp.score != newScore || scoreComp.combo != newCombo) {
            world.set(scoreEntity, ScoreComponent(
                score = newScore,
                combo = newCombo,
                maxCombo = newMaxCombo,
                judgmentCounts = newCounts
            ))
        }
    }
}
