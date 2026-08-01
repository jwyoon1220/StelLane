package io.github.jwyoon1220.app.story

import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.core.story.StoryScene
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderCommand

/**
 * 하나의 [StoryScene](대사 시퀀스)를 이미지 기반 시각소설 형식으로 재생하는 씬.
 * GUI 버튼 없이 클릭 또는 Enter/Space 키로만 진행하며, 대사가 모두 끝나면 [onComplete]를 호출합니다.
 */
class StoryCutsceneScene(
    private val ctx: GameContext,
    private val storyScene: StoryScene,
    private val onComplete: () -> Unit
) : Scene() {

    private val dialogueRenderer = DialogueRenderer()
    private lateinit var player: StoryScenePlayer
    private var pendingComplete = false

    override fun enter() {
        super.enter()
        player = StoryScenePlayer(storyScene)
        // 대사가 비어있는 방어적 케이스 — enter() 안에서 즉시 navigate하면 재진입 문제가 있으므로
        // 다음 update 틱에서 처리합니다.
        pendingComplete = player.isFinished
        ctx.inputManager.clearEvents()
        register(CutsceneRenderSystem())
    }

    override fun onUpdate(deltaTime: Double) {
        if (pendingComplete) {
            pendingComplete = false
            onComplete()
        }
    }

    private fun advanceOrFinish() {
        if (player.isFinished) return
        player.advance()
        if (player.isFinished) onComplete()
    }

    override fun keyPressed(key: Int, mods: Int) {
        if (key == Keys.ENTER || key == Keys.SPACE) advanceOrFinish()
    }

    override fun mouseClicked(x: Float, y: Float, button: Int, mods: Int) {
        advanceOrFinish()
    }

    private inner class CutsceneRenderSystem : RenderProducer {
        override fun update(world: World, input: InputSnapshot, deltaTime: Double) = Unit
        override fun produce(world: World, out: MutableList<RenderCommand>) {
            out.add(RenderCommand.LegacyDrawContext { dialogueRenderer.render(this, storyScene, player.dialogueIndex) })
        }
    }
}
