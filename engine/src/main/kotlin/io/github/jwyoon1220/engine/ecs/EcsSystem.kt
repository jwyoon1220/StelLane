package io.github.jwyoon1220.engine.ecs

/**
 * ECS 시스템 인터페이스.
 *
 * 모든 시스템은 [update]를 통해 World의 컴포넌트를 읽고 변경합니다.
 * 렌더링은 [RenderProducer]를 추가 구현해 RenderCommand를 발행하는 방식으로 분리됩니다.
 *
 * ## 스레드 규칙
 * - [MainThreadSystem]으로 표시된 시스템: GLFW 메인 스레드에서만 실행.
 * - 그 외 시스템: 기본적으로 메인 스레드에서 실행 (현재 구현에서는 단일 스레드).
 *
 * 이름이 `java.lang.System`과 충돌하므로 import 시 `io.github.jwyoon1220.engine.ecs.EcsSystem`으로
 * 명시하거나, 파일 내에서 `java.lang.System`을 fully-qualified로 참조하세요.
 */
interface EcsSystem {
    /**
     * 매 프레임 호출됩니다.
     *
     * @param world     현재 씬의 World (엔터티/컴포넌트 접근)
     * @param input     이번 프레임 입력 스냅샷
     * @param deltaTime 이전 프레임과의 경과 시간 (초 단위)
     */
    fun update(world: World, input: InputSnapshot, deltaTime: Double)
}

/**
 * GLFW 메인 스레드에서만 실행 가능한 시스템 마커.
 * (렌더 시스템, ImGui 시스템, GLFW 입력 시스템 등)
 */
interface MainThreadSystem : EcsSystem

/**
 * 렌더 커맨드를 생산하는 시스템.
 * [update] 이후 Renderer가 [produce]를 호출해 이번 프레임의 그리기 명령을 수집합니다.
 */
interface RenderProducer : MainThreadSystem {
    /**
     * 이번 프레임에 그릴 [io.github.jwyoon1220.engine.render.RenderCommand]를 [out]에 추가합니다.
     * 여기서 실제 그리기는 하지 않습니다 — 명령 수집만 합니다.
     */
    fun produce(world: World, out: MutableList<io.github.jwyoon1220.engine.render.RenderCommand>)
}
