package io.github.jwyoon1220.app

import io.github.jwyoon1220.engine.multiplayer.MultiplayerManager
import io.github.jwyoon1220.app.render.NoteRenderer
import io.github.jwyoon1220.core.song.SongManager
import io.github.jwyoon1220.core.story.StoryDataLoader
import io.github.jwyoon1220.core.story.StoryMode
import io.github.jwyoon1220.core.story.StoryProgressManager
import io.github.jwyoon1220.engine.GameLoop
import io.github.jwyoon1220.engine.InputManager
import io.github.jwyoon1220.engine.Renderer
import io.github.jwyoon1220.engine.SceneRouter
import io.github.jwyoon1220.engine.VideoBackground
import io.github.jwyoon1220.engine.data.pool.ObjectPool
import io.github.jwyoon1220.engine.data.pool.VisualNote

/** 모든 상태(State)가 공유하는 공통 의존성 모음. */
class GameContext(
    val sceneRouter: SceneRouter,
    val songManager: SongManager,
    val videoBackground: VideoBackground,
    val notePool: ObjectPool<VisualNote>,
    val inputManager: InputManager,
    val windowManager: WindowManager,
    val noteRenderer: NoteRenderer
) {
    /** 스토리 모드 정적 데이터(챕터/대사/필요곡) — `assets/story/story_data.json`에서 1회 로드. */
    val storyModeData: StoryMode by lazy { StoryDataLoader.loadFromResources() }
    /** 스토리 모드 진행도 저장/로드. */
    val storyProgressMgr: StoryProgressManager = StoryProgressManager()
    /** 게임 루프. Main에서 생성 직후 주입됩니다. */
    lateinit var gameLoop: GameLoop
    /** 렌더러. Main에서 생성 직후 주입됩니다. */
    lateinit var renderer: Renderer
    /** 멀티플레이어 매니저. 멀티플레이어 세션 중에만 설정됩니다. */
    var multiplayerManager: MultiplayerManager? = null
}
