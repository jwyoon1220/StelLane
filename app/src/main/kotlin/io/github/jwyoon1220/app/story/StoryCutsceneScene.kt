package io.github.jwyoon1220.app.story

import io.github.jwyoon1220.app.GameContext
import io.github.jwyoon1220.core.story.LoadedStoryPack
import io.github.jwyoon1220.core.story.StoryScene
import io.github.jwyoon1220.engine.Keys
import io.github.jwyoon1220.engine.ecs.InputSnapshot
import io.github.jwyoon1220.engine.ecs.RenderProducer
import io.github.jwyoon1220.engine.ecs.Scene
import io.github.jwyoon1220.engine.ecs.World
import io.github.jwyoon1220.engine.render.RenderCommand
import java.io.File

/**
 * 하나의 [StoryScene](대사 시퀀스)를 이미지 기반 시각소설 형식으로 재생하는 씬.
 * GUI 버튼 없이 클릭 또는 Enter/Space 키로만 진행하며, 대사가 모두 끝나면 [onComplete]를 호출합니다.
 * [StoryScene.backgroundVideo]가 설정되어 있으면 [GameContext.videoBackground]로 배경 영상을 재생합니다
 * (다른 Scene들과 같은 방식 — `rendersBackground`가 기본값 false라 Renderer가 화면 전체에 자동으로 그려줍니다).
 */
class StoryCutsceneScene(
    private val ctx: GameContext,
    private val pack: LoadedStoryPack,
    private val storyScene: StoryScene,
    private val onComplete: () -> Unit
) : Scene() {

    private val dialogueRenderer = DialogueRenderer(pack.imagesDir)
    private lateinit var player: StoryScenePlayer
    private var pendingComplete = false
    private var videoPlaying = false

    override fun enter() {
        super.enter()
        player = StoryScenePlayer(storyScene)
        // 대사가 비어있는 방어적 케이스 — enter() 안에서 즉시 navigate하면 재진입 문제가 있으므로
        // 다음 update 틱에서 처리합니다.
        pendingComplete = player.isFinished
        ctx.inputManager.clearEvents()
        register(CutsceneRenderSystem())

        val videoFile = storyScene.backgroundVideo?.let { File(pack.videosDir, it) }
        if (videoFile != null && videoFile.isFile) {
            videoPlaying = true
            // 대사가 영상보다 오래 이어질 수 있으므로 끝까지 재생되면 처음부터 반복합니다.
            ctx.videoBackground.onFinished = { ctx.videoBackground.play(videoFile.absolutePath) }
            ctx.videoBackground.play(videoFile.absolutePath)
        }
    }

    override fun exit() {
        if (videoPlaying) {
            ctx.videoBackground.onFinished = null
            ctx.videoBackground.stop()
            videoPlaying = false
        }
        super.exit()
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
