package io.github.jwyoon1220.engine.ecs

import kotlin.reflect.KClass

/**
 * ECS World — 엔터티와 컴포넌트의 중앙 저장소.
 *
 * ## 사용 예
 * ```kotlin
 * val world = World()
 * val e = world.create()
 * world.set(e, NoteComponent(lane = 0, timeMs = 1500L))
 * val note = world.get<NoteComponent>(e)       // NoteComponent?
 * world.entitiesWith<NoteComponent>().forEach { ... }
 * world.destroy(e)
 * ```
 *
 * ## 스레드 안전성
 * World는 **메인 스레드 전용**입니다. 다른 스레드에서 접근하지 마세요.
 */
class World {

    private var nextId: Long = 1L
    @PublishedApi internal val alive = HashSet<Entity>()

    @PublishedApi
    internal val storages = HashMap<KClass<out Component>, ComponentStorage<*>>()

    // ── 엔터티 수명 주기 ────────────────────────────────────────────────────

    /** 새 엔터티를 생성하고 ID를 반환합니다. */
    fun create(): Entity {
        val id = nextId++
        alive.add(id)
        return id
    }

    /**
     * 엔터티를 파괴합니다.
     * 모든 저장소에서 해당 엔터티의 컴포넌트를 제거합니다.
     */
    fun destroy(entity: Entity) {
        if (alive.remove(entity)) {
            storages.values.forEach { it.entityRemove(entity) }
        }
    }

    /** 엔터티가 현재 살아있는지 확인합니다. */
    fun isAlive(entity: Entity): Boolean = entity in alive

    /** 현재 살아있는 모든 엔터티의 집합(읽기 전용 뷰). */
    val entities: Set<Entity> get() = alive

    // ── 컴포넌트 접근 ────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    internal fun <T : Component> storageOf(type: KClass<T>): ComponentStorage<T> =
        storages.getOrPut(type) { ComponentStorage<T>() } as ComponentStorage<T>

    /** 엔터티에 컴포넌트를 설정합니다. */
    inline fun <reified T : Component> set(entity: Entity, component: T) =
        storageOf(T::class).set(entity, component)

    /** 엔터티의 컴포넌트를 반환합니다. 없으면 null. */
    inline fun <reified T : Component> get(entity: Entity): T? =
        storageOf(T::class).get(entity)

    /** 엔터티의 컴포넌트를 반환합니다. 없으면 예외. */
    inline fun <reified T : Component> require(entity: Entity): T =
        get<T>(entity) ?: error("Entity $entity has no component ${T::class.simpleName}")

    /** 엔터티가 해당 컴포넌트를 갖고 있는지 확인합니다. */
    inline fun <reified T : Component> has(entity: Entity): Boolean =
        storageOf(T::class).has(entity)

    /** 엔터티의 컴포넌트를 제거합니다. */
    inline fun <reified T : Component> remove(entity: Entity) =
        storageOf(T::class).remove(entity)

    // ── 쿼리 ────────────────────────────────────────────────────────────────

    /** 특정 컴포넌트를 가진 살아있는 모든 엔터티를 순회합니다. */
    @JvmName("entitiesWithOne")
    inline fun <reified T : Component> entitiesWith(): Sequence<Entity> =
        storageOf(T::class).entries()
            .asSequence()
            .map { it.key }
            .filter { it in alive }

    /** 두 컴포넌트를 모두 가진 살아있는 모든 엔터티를 순회합니다. */
    @JvmName("entitiesWithTwo")
    inline fun <reified A : Component, reified B : Component> entitiesWith(): Sequence<Entity> =
        storageOf(A::class).entries()
            .asSequence()
            .map { it.key }
            .filter { it in alive && has<B>(it) }

    /** 세 컴포넌트를 모두 가진 살아있는 모든 엔터티를 순회합니다. */
    @JvmName("entitiesWithThree")
    inline fun <reified A : Component, reified B : Component, reified C : Component> entitiesWith(): Sequence<Entity> =
        storageOf(A::class).entries()
            .asSequence()
            .map { it.key }
            .filter { it in alive && has<B>(it) && has<C>(it) }

    // ── 수명 주기 ────────────────────────────────────────────────────────────

    /** 모든 엔터티와 컴포넌트를 제거합니다. Scene 종료 시 호출됩니다. */
    fun clear() {
        alive.clear()
        storages.values.forEach { it.entityClear() }
    }
}

// ── 내부 확장 (타입 소거 우회) ────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun ComponentStorage<*>.entityRemove(entity: Entity) =
    (this as ComponentStorage<Component>).remove(entity)

@Suppress("UNCHECKED_CAST")
private fun ComponentStorage<*>.entityClear() =
    (this as ComponentStorage<Component>).clear()
