package io.github.jwyoon1220.engine.ecs

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

/**
 * 단일 컴포넌트 타입에 대한 엔터티 → 컴포넌트 매핑 저장소.
 *
 * 현재는 HashMap 기반의 단순 구현입니다.
 * 노트 수가 많아지면 SparseSet 기반으로 교체할 수 있도록 인터페이스를 통해 사용하세요.
 */
class ComponentStorage<T : Component> {

    private val data = Object2ObjectOpenHashMap<Entity, T>()

    /** 엔터티에 컴포넌트를 설정합니다. 이미 있으면 교체됩니다. */
    fun set(entity: Entity, component: T) {
        data[entity] = component
    }

    /** 엔터티의 컴포넌트를 반환합니다. 없으면 null. */
    fun get(entity: Entity): T? = data[entity]

    /** 엔터티의 컴포넌트를 제거합니다. */
    fun remove(entity: Entity) { data.remove(entity) }

    /** 엔터티가 이 컴포넌트를 갖고 있는지 확인합니다. */
    fun has(entity: Entity): Boolean = entity in data

    /** 이 컴포넌트를 가진 모든 (Entity, T) 쌍을 순회합니다. */
    fun entries(): Iterable<Map.Entry<Entity, T>> = data.entries

    /** 저장된 컴포넌트의 수. */
    val size: Int get() = data.size

    /** 모든 데이터를 삭제합니다. */
    fun clear() { data.clear() }
}
