package io.github.jwyoon1220.engine.ecs

import io.github.jwyoon1220.engine.DrawContext
import io.github.jwyoon1220.engine.render.RenderCommand
import io.github.jwyoon1220.engine.render.executeOnDrawContext
import it.unimi.dsi.fastutil.objects.ObjectArrayList

/**
 * ECS 씬 — World + 시스템 파이프라인을 관리하는 게임 화면 단위.
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
abstract class Scene {

    /** 이 씬의 ECS 엔터티/컴포넌트 저장소. */
    val world = World()

    /** 씬 내 시스템 간 통신 버스. */
    val eventBus = EventBus()

    private val systems   = ObjectArrayList<EcsSystem>()
    private val producers = ObjectArrayList<RenderProducer>()

    /** [gatherRenderCommands]가 매 프레임 재사용하는 버퍼 — 새 ArrayList 할당을 프레임마다 피합니다. */
    private val renderCommandsBuffer = ArrayList<RenderCommand>()

    /** 마지막으로 빌드된 InputSnapshot (매 프레임 update 직전 갱신). */
    var lastInput: InputSnapshot = InputSnapshot.EMPTY
        private set

    // ── 시스템 등록 ────────────────────────────────────────────────────────────

    /** 시스템을 등록 순서대로 추가합니다. update는 등록 순서대로 실행됩니다. */
    protected fun register(vararg sys: EcsSystem) {
        systems.addAll(sys)
        for (s in sys) if (s is RenderProducer) producers.add(s)
    }

    // ── 생명주기 / 렌더 ───────────────────────────────────────────────────────

    /** 씬 진입. super.enter()를 항상 호출하세요. */
    open fun enter() {}

    /**
     * 씬 종료. World와 EventBus를 초기화합니다.
     * 커스텀 정리가 필요하면 super.exit() 전에 처리하세요.
     */
    open fun exit() {
        systems.clear()
        producers.clear()
        world.clear()
        eventBus.clear()
    }

    /**
     * 매 프레임 update.
     * legacy 경로: [onUpdate]를 override해 InputSnapshot 없이 deltaTime만 사용할 수 있습니다.
     * ECS 경로:    [tickSystems]가 자동으로 호출됩니다.
     */
    open fun update(deltaTime: Double) {
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
     *
     * 반환된 리스트는 [renderCommandsBuffer]를 그대로 노출하는 뷰이므로, 호출 측은 같은 프레임 내에서
     * 즉시 소비해야 합니다(현재 모든 호출부가 그렇게 사용합니다). 다음 프레임의 [gatherRenderCommands]
     * 호출 시 내용이 지워지고 새로 채워집니다.
     */
    fun gatherRenderCommands(): List<RenderCommand> {
        renderCommandsBuffer.clear()
        producers.forEach { it.produce(world, renderCommandsBuffer) }
        return renderCommandsBuffer
    }

    /**
     * 기본 렌더 구현 — DrawContext를 통해 ECS 커맨드를 실행합니다.
     *
     * ECS 커맨드 기반으로 완전히 이식된 씬에서는 이 메서드를 override할 필요가 없습니다.
     * Legacy 씬에서 DrawContext를 직접 사용하려면 이 메서드를 override하세요.
     */
    open fun render(g: DrawContext) {
        val commands = gatherRenderCommands()
        if (commands.isNotEmpty()) {
            executeCommandsOnDrawContext(g, commands)
        }
    }

    // ── 입력 콜백 (기본 구현: no-op) ─────────────────────────────────────────

    open fun keyPressed (key: Int, mods: Int) {}
    open fun keyReleased(key: Int, mods: Int) {}
    /** GLFW character callback — Unicode 코드포인트 */
    open fun keyTyped   (codepoint: Int)      {}
    open fun mousePressed (x: Float, y: Float, button: Int, mods: Int) {}
    open fun mouseClicked (x: Float, y: Float, button: Int, mods: Int) {}
    open fun mouseReleased(x: Float, y: Float, button: Int, mods: Int) {}
    open fun mouseDragged (x: Float, y: Float, button: Int)            {}
    open fun mouseScrolled(dy: Double) {}

    /** State가 배경(비디오 등)을 자체적으로 그릴지 여부. true이면 Renderer가 기본 배경을 그리지 않습니다. */
    open val rendersBackground: Boolean get() = false

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
