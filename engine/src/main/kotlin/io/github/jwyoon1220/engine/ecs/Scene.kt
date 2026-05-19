package io.github.jwyoon1220.engine.ecs

import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.GameState
import io.github.jwyoon1220.engine.render.RenderCommand
import io.github.jwyoon1220.engine.render.executeOnDrawContext
import it.unimi.dsi.fastutil.objects.ObjectArrayList

/**
 * ECS 씬 — World + 시스템 파이프라인을 관리하는 게임 화면 단위.
 *
 * 기존 [GameState]를 구현하므로 마이그레이션 기간 중에는 [StateManager]에 그대로 등록할 수 있습니다.
 * ECS 코드와 legacy 코드를 공존시키다가 점진적으로 legacy 부분을 시스템으로 이식하세요.
 *
 * ## 전형적인 사용 패턴
 * ```kotlin
 * class PlayScene(ctx: GameContext) : Scene() {
 *
 *     private val spawnSystem   = NoteSpawnSystem(ctx.chart)
 *     private val inputSystem   = NoteInputSystem(ctx.inputManager)
 *     private val judgmentSystem = JudgmentSystem()
 *     private val renderSystem  = NoteRenderSystem()
 *
 *     override fun enter() {
 *         super.enter()
 *         register(spawnSystem, inputSystem, judgmentSystem, renderSystem)
 *     }
 * }
 * ```
 *
 * ## 렌더 흐름 (ECS 경로)
 * 1. [update] → 각 시스템의 [EcsSystem.update] 순서대로 호출
 * 2. [render] → [RenderProducer] 시스템에서 커맨드 수집 → 백엔드에 제출
 *
 * ## 렌더 흐름 (Legacy DrawContext 경로)
 * [render] override에서 `g.drawXxx()`를 직접 호출합니다.
 * [DrawContextLegacyCommand] 를 통해 ECS 커맨드와 혼용도 가능합니다.
 */
abstract class Scene : GameState {

    /** 이 씬의 ECS 엔터티/컴포넌트 저장소. */
    val world = World()

    /** 씬 내 시스템 간 통신 버스. */
    val eventBus = EventBus()

    private val systems = ObjectArrayList<EcsSystem>()

    /** 마지막으로 빌드된 InputSnapshot (매 프레임 update 직전 갱신). */
    var lastInput: InputSnapshot = InputSnapshot.EMPTY
        private set

    // ── 시스템 등록 ────────────────────────────────────────────────────────────

    /** 시스템을 등록 순서대로 추가합니다. update는 등록 순서대로 실행됩니다. */
    protected fun register(vararg sys: EcsSystem) { systems.addAll(sys) }

    // ── GameState 구현 ────────────────────────────────────────────────────────

    /** 씬 진입. super.enter()를 항상 호출하세요. */
    override fun enter() {}

    /**
     * 씬 종료. World와 EventBus를 초기화합니다.
     * 커스텀 정리가 필요하면 super.exit() 전에 처리하세요.
     */
    override fun exit() {
        systems.clear()
        world.clear()
        eventBus.clear()
    }

    /**
     * 매 프레임 update.
     * legacy 경로: [onUpdate]를 override해 InputSnapshot 없이 deltaTime만 사용할 수 있습니다.
     * ECS 경로:    [tickSystems]가 자동으로 호출됩니다.
     */
    override fun update(deltaTime: Double) {
        tickSystems(lastInput, deltaTime)
        onUpdate(deltaTime)
    }

    /**
     * InputSnapshot이 필요 없는 legacy update 진입점.
     * ECS 시스템으로 완전히 이식되면 이 메서드는 사라집니다.
     */
    protected open fun onUpdate(deltaTime: Double) {}

    /**
     * 등록된 모든 시스템의 [EcsSystem.update]를 순서대로 실행합니다.
     * [InputManager]가 InputSnapshot을 생성하면 이 메서드에 전달됩니다.
     */
    fun tickSystems(input: InputSnapshot, deltaTime: Double) {
        lastInput = input
        systems.forEach { it.update(world, input, deltaTime) }
    }

    /**
     * [GameLoop]이 [update] 호출 전에 이번 프레임 입력을 주입합니다.
     *
     * [update] → [tickSystems]가 [lastInput]을 사용하므로
     * 이 메서드를 반드시 [update] 이전에 호출해야 합니다.
     */
    fun injectInput(snapshot: InputSnapshot) {
        lastInput = snapshot
    }

    /**
     * ECS [RenderProducer] 시스템에서 이번 프레임의 렌더 커맨드를 수집합니다.
     * Renderer가 호출하며, 반환된 커맨드 목록을 RendererBackend에 제출합니다.
     */
    fun gatherRenderCommands(): List<RenderCommand> {
        val out = ArrayList<RenderCommand>()
        systems.filterIsInstance<RenderProducer>().forEach { it.produce(world, out) }
        return out
    }

    /**
     * 기본 렌더 구현 — DrawContext를 통해 ECS 커맨드를 실행합니다.
     *
     * ECS 커맨드 기반으로 완전히 이식된 씬에서는 이 메서드를 override할 필요가 없습니다.
     * Legacy 씬에서 DrawContext를 직접 사용하려면 이 메서드를 override하세요.
     */
    override fun render(g: DrawContext) {
        val commands = gatherRenderCommands()
        if (commands.isNotEmpty()) {
            executeCommandsOnDrawContext(g, commands)
        }
    }

    // ── 내부: DrawContext 위에서 RenderCommand 실행 ────────────────────────────

    private fun executeCommandsOnDrawContext(g: DrawContext, commands: List<RenderCommand>) {
        for (cmd in commands) {
            when (cmd) {
                is RenderCommand.LegacyDrawContext -> cmd.block(g)
                // 나머지 커맨드 타입은 NanoVGBackend가 처리 — DrawContext fallback 실행
                else -> cmd.executeOnDrawContext(g)
            }
        }
    }
}
