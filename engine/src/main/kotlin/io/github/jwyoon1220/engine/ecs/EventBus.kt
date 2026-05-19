package io.github.jwyoon1220.engine.ecs

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import kotlin.reflect.KClass

/**
 * ECS 이벤트 버스.
 *
 * 시스템 간 직접 참조 없이 이벤트를 교환할 때 사용합니다.
 * 메인 스레드에서만 사용합니다.
 *
 * ## 사용 예
 * ```kotlin
 * // 구독
 * eventBus.on<NoteHitEvent> { e -> scoreSystem.applyHit(e) }
 *
 * // 발행
 * eventBus.emit(NoteHitEvent(lane = 2, judgment = Judgment.PERFECT))
 * ```
 */
class EventBus {

    @PublishedApi
    internal val listeners = Object2ObjectOpenHashMap<KClass<out EcsEvent>, MutableList<(EcsEvent) -> Unit>>()

    /** 이벤트 타입 [T]를 구독합니다. */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : EcsEvent> on(noinline handler: (T) -> Unit) {
        listeners
            .getOrPut(T::class) { mutableListOf() }
            .add(handler as (EcsEvent) -> Unit)
    }

    /** 이벤트를 발행합니다. 모든 구독자가 즉시(동기) 호출됩니다. */
    fun emit(event: EcsEvent) {
        listeners[event::class]?.forEach { it(event) }
    }

    /** 모든 구독을 제거합니다. Scene 종료 시 호출됩니다. */
    fun clear() = listeners.clear()
}

/**
 * 모든 ECS 이벤트의 마커 인터페이스.
 *
 * 이벤트는 **불변 값 객체(data class / sealed class)**로 구현하세요.
 *
 * ```kotlin
 * data class NoteHitEvent(val entity: Entity, val judgment: Judgment) : EcsEvent
 * data class SceneChangeEvent(val nextSceneId: String) : EcsEvent
 * ```
 */
interface EcsEvent
