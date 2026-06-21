package io.github.jwyoon1220.engine

import io.github.jwyoon1220.engine.ecs.Scene

/**
 * GLFW 메인 스레드에서 실행되는 게임 루프.
 *
 * GLFW 는 glfwPollEvents() 를 메인 스레드에서만 호출할 수 있으므로
 * 루프 전체가 메인 스레드에서 작동합니다.
 * [start] 는 블로킹 호출이며, 창이 닫힐 때까지 반환되지 않습니다.
 */
class GameLoop(
    private val window: GLFWWindow,
    private val stateManager: SceneRouter,
    private val renderer: Renderer,
    /**
     * InputSnapshot을 빌드해 현재 [Scene]에 주입하기 위한 InputManager.
     * null이면 ECS InputSnapshot 주입을 건너뜁니다 (하위 호환).
     */
    private val inputManager: InputManager? = null
) {
    /** 목표 FPS. 언제든지 변경 가능 — 다음 루프 사이클부터 반영됩니다. */
    @Volatile var targetFPS: Int = 60
        set(v) { field = v.coerceAtLeast(1); optimalTimeNs = 1_000_000_000L / field }

    @Volatile private var optimalTimeNs = 1_000_000_000L / 60

    /**
     * 1초마다 실제 FPS 값과 함께 호출되는 콜백.
     * 메인 스레드에서 호출됩니다.
     */
    var onFpsUpdate: ((fps: Int) -> Unit)? = null

    /**
     * 메인 스레드 블로킹 루프.
     * 창이 닫히거나 [window.shouldClose] 가 true 가 될 때까지 실행됩니다.
     */
    fun start() {
        var lastLoopTime = System.nanoTime()
        var frameCount   = 0
        var fpsWindowStart = System.nanoTime()

        while (!window.shouldClose()) {
            val now = System.nanoTime()
            // 최대 100ms로 cap — 디버거 중단이나 GC 스톱으로 누적된 시간이 게임에 한번에 반영되는 것을 방지
            val delta = ((now - lastLoopTime) / 1_000_000_000.0).coerceAtMost(0.1)
            lastLoopTime = now

            // 1. GLFW 이벤트 처리 (콜백 → InputManager 로 라우팅)
            window.pollEvents()

            // 2-a. ECS Scene이면 InputSnapshot을 주입합니다
            //      (Scene.update()가 내부에서 tickSystems(lastInput, delta)를 호출)
            val currentState = stateManager.current
            if (inputManager != null && currentState is Scene) {
                val snap = inputManager.buildSnapshot(now)
                currentState.injectInput(snap)
            }

            // 2-b. 상태 업데이트 로직
            stateManager.update(delta)

            // 3. 렌더링
            renderer.renderFrame()
            window.swapBuffers()

            // FPS 측정
            frameCount++
            val elapsed = System.nanoTime() - fpsWindowStart
            if (elapsed >= 1_000_000_000L) {
                onFpsUpdate?.invoke((frameCount * 1_000_000_000L / elapsed).toInt())
                frameCount     = 0
                fpsWindowStart = System.nanoTime()
            }

            // 4. 고정밀 프레임 대기
            // 1ms 단위로 sleep → 남은 시간이 1ms 이하가 되면 스핀 대기
            // (parkNanos 는 Windows에서 OS 스케줄러 정밀도로 인해 수백 µs씩 실제로 더 자므로 사용 안 함)
            val deadline = lastLoopTime + optimalTimeNs
            var remaining = deadline - System.nanoTime()
            while (remaining > 1_000_000L) {
                try { Thread.sleep(1) }
                catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
                remaining = deadline - System.nanoTime()
            }
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
        }
    }
}

