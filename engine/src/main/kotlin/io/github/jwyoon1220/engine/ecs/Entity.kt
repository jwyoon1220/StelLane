package io.github.jwyoon1220.engine.ecs

/**
 * ECS 엔터티 ID.
 * 값 자체가 정체성이며, 컴포넌트 저장소의 키로 쓰입니다.
 * 0은 유효하지 않은 엔터티이므로 World는 항상 1부터 할당합니다.
 */
typealias Entity = Long

/** 유효하지 않은 엔터티를 나타내는 sentinel 값. */
const val NULL_ENTITY: Entity = 0L
