package io.github.jwyoon1220.engine.ecs

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap

/**
 * 단일 컴포넌트 타입에 대한 엔터티 → 컴포넌트 매핑 저장소.
 *
 * Long2ObjectOpenHashMap 을 사용해 Entity(Long) boxing 오버헤드를 제거합니다.
 * 노트 수가 많아지면 SparseSet 기반으로 교체할 수 있도록 인터페이스를 통해 사용하세요.
 */
class ComponentStorage<T : Component> {

    private val data = Long2ObjectOpenHashMap<T>()

    fun set(entity: Entity, component: T) {
        data.put(entity, component)
    }

    fun get(entity: Entity): T? = data.get(entity)

    fun remove(entity: Entity) { data.remove(entity) }

    fun has(entity: Entity): Boolean = data.containsKey(entity)

    fun entries(): Iterable<Map.Entry<Long, T>> {
        @Suppress("UNCHECKED_CAST")
        return data.long2ObjectEntrySet() as Iterable<Map.Entry<Long, T>>
    }

    val size: Int get() = data.size

    fun clear() { data.clear() }
}
