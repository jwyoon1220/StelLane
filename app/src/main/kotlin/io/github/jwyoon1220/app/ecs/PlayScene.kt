package io.github.jwyoon1220.app.ecs

import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.SongEntry
import io.github.jwyoon1220.core.scoring.ScoreEngine
import io.github.jwyoon1220.engine.DrawContext

/**
 * ECS 기반 게임플레이 씬.
 *
 * PlayState의 로직을 ECS 시스템으로 분해한 구현.
 * 각 시스템은 독립적으로 작동하고 World를 공유.
 *
 * 마이그레이션 단계:
 * 1. PlayScene을 PlayState와 병렬로 생성
 * 2. useEcs 플래그로 A/B 테스트 (레거시 vs ECS)
 * 3. 골든 리플레이와 프레임 동등성 검증
 * 4. 레거시 PlayState 제거
 */
class PlayScene(
    private val songEntry: SongEntry,
    private val chart: Chart
) : Scene() {

    private lateinit var scoreEngine: ScoreEngine

    init {
        // 시스템들 등록
        register(
            NoteSpawnSystem(chart.notes),
            JudgmentSystem(),
            PlayRenderSystem()
        )
    }

    override fun enter() {
        super.enter()

        // ScoreEngine 초기화 (호환성)
        scoreEngine = ScoreEngine(chart.notes.size)

        // ECS 초기화: 차트 엔티티 생성
        val bpm = (songEntry.song.bpm ?: 120).toFloat()
        val chartEntity = ChartAdapter.createChartEntities(world, chart, chart.notes, bpm)

        // ScoreSystem에 ScoreEngine 전달 (TODO: 더 나은 패턴 사용)
        // 지금은 시스템이 이미 등록되었으므로, 레지스트리를 통해 찾아야 함
        // register(ScoreSystem(scoreEngine))
    }

    override fun exit() {
        super.exit()
    }

    override fun update(deltaTime: Double) {
        super.update(deltaTime)
    }

    override fun render(g: DrawContext) {
        super.render(g)
    }
}
