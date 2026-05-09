package io.github.jwyoon1220.app

import io.github.jwyoon1220.core.StateManager
import io.github.jwyoon1220.core.song.SongManager
import io.github.jwyoon1220.engine.InputManager
import io.github.jwyoon1220.engine.VideoBackground
import io.github.jwyoon1220.engine.pool.ObjectPool
import io.github.jwyoon1220.engine.pool.VisualNote

/** 모든 상태(State)가 공유하는 공통 의존성 모음. */
data class GameContext(
    val stateManager: StateManager,
    val songManager: SongManager,
    val videoBackground: VideoBackground,
    val notePool: ObjectPool<VisualNote>,
    val inputManager: InputManager,
    val windowManager: WindowManager
)
