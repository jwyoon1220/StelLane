package io.github.jwyoon1220.app.story

import io.github.jwyoon1220.core.story.StoryScene

/**
 * 하나의 [StoryScene](대사 시퀀스)의 진행 상태를 관리합니다.
 * 클릭/Enter 입력으로 [advance]를 호출해 다음 대사로 넘어가고,
 * 모든 대사가 끝나면 [isFinished]가 true가 됩니다.
 */
class StoryScenePlayer(val scene: StoryScene) {

    var dialogueIndex: Int = 0
        private set

    val isFinished: Boolean
        get() = scene.dialogues.isEmpty() || dialogueIndex >= scene.dialogues.size

    /** 현재 표시 중인 대사가 이 장면의 마지막 대사인지 여부. */
    val isLastLine: Boolean
        get() = dialogueIndex == scene.dialogues.lastIndex

    /** 다음 대사로 진행합니다. 이미 끝났다면 아무 일도 일어나지 않습니다. */
    fun advance() {
        if (!isFinished) dialogueIndex++
    }
}
