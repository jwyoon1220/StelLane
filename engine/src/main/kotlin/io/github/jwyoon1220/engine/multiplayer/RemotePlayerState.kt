package io.github.jwyoon1220.engine.multiplayer

/** 원격 플레이어(또는 관전 대상)의 실시간 상태 스냅샷. 네트워크 스레드에서 갱신, 게임 루프에서 읽기만 함. */
data class RemotePlayerState(
    val id: String,
    val name: String,
    val role: String,                            // "player" | "spectator"
    @Volatile var score: Int = 0,
    @Volatile var combo: Int = 0,
    @Volatile var counts: IntArray = IntArray(4),    // [PERFECT, GREAT, GOOD, MISS] — always replace ref, never mutate in-place
    @Volatile var accuracy: Float = 0f,
    @Volatile var laneHeld: BooleanArray = BooleanArray(4), // always replace ref, never mutate in-place
    @Volatile var finished: Boolean = false
) {
    override fun equals(other: Any?): Boolean = other is RemotePlayerState && id == other.id
    override fun hashCode(): Int = id.hashCode()
}
