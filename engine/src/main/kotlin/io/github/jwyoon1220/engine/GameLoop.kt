package io.github.jwyoon1220.engine

import io.github.jwyoon1220.core.StateManager
import java.awt.Color
import java.awt.Graphics2D
import java.util.concurrent.locks.LockSupport
import java.awt.RenderingHints
import java.awt.Toolkit
import javax.swing.JComponent
import kotlin.concurrent.thread

// 게임 메인 루프: 로직 업데이트와 렌더링 호출을 스레드로 분리해 고정 프레임/틱을 유지합니다.
class GameLoop(
    private val stateManager: StateManager,
    private val renderTarget: JComponent
) {
    @Volatile
    private var isRunning = false

    /** 목표 FPS. 언제든지 변경 가능 — 다음 루프 사이클부터 반영됩니다. */
    @Volatile var targetFPS: Int = 60
        set(v) { field = v.coerceAtLeast(1); optimalTimeNs = 1_000_000_000L / field }

    @Volatile private var optimalTimeNs = 1_000_000_000L / 60

    /**
     * 1초마다 실제 FPS 값과 함께 호출되는 콜백.
     * EDT가 아닌 GameLoopThread에서 호출되므로 UI 업데이트 시 SwingUtilities.invokeLater 사용.
     */
    var onFpsUpdate: ((fps: Int) -> Unit)? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(name = "GameLoopThread") {
            var lastLoopTime = System.nanoTime()

            // FPS 측정용
            var frameCount = 0
            var fpsWindowStart = System.nanoTime()

            while (isRunning) {
                val now = System.nanoTime()
                val updateLength = now - lastLoopTime
                lastLoopTime = now

                val delta = updateLength / 1000000000.0

                // 1. 상태 업데이트 로직 처리
                stateManager.update(delta)

                // 2. 렌더링 요청
                renderTarget.repaint()
                Toolkit.getDefaultToolkit().sync()  // 디스플레이 파이프라인 플러쉬 (티어링 방지)

                // FPS 카운트
                frameCount++
                val elapsed = System.nanoTime() - fpsWindowStart
                if (elapsed >= 1_000_000_000L) {
                    val fps = (frameCount * 1_000_000_000L / elapsed).toInt()
                    onFpsUpdate?.invoke(fps)
                    frameCount = 0
                    fpsWindowStart = System.nanoTime()
                }

                // 3. 고정밀 프레임 대기 (Spin-wait + Thread.sleep 혼합)
                // Windows에서 Thread.sleep은 최대 15.6ms의 오차가 발생하므로,
                // 남은 시간이 2ms 이상일 때만 sleep하고, 나머지는 yield()로 스핀 대기하여 정확도를 극대화합니다.
                while (true) {
                    val nowNano = System.nanoTime()
                    val passed = nowNano - lastLoopTime
                    val remaining = optimalTimeNs - passed
                    if (remaining <= 0) break

                    if (remaining > 2_000_000L) {
                        try {
                            Thread.sleep(1)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    } else {
                        // yield()는 CPU를 양보하지 않고 소진 — parkNanos로 대체해 CPU 절감
                        LockSupport.parkNanos(100_000L)  // 0.1ms 단위 슬립
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning = false
    }
}

/**
 * 단일 Swing 컴포넌트에서 비디오 프레임 + 게임 UI를 한 번에 그립니다.
 *
 * 1280×720 논리 해상도를 유지하면서 창 크기에 맞게 letterbox/pillarbox 스케일링합니다.
 * VideoBackground 콜백 방식 덕분에 AWT Canvas가 없으므로
 * heavyweight-over-lightweight 오버드로 문제가 발생하지 않습니다.
 */
class RenderPanel(
    private val stateManager: StateManager,
    private val videoBackground: VideoBackground? = null
) : JComponent() {

    companion object {
        const val DESIGN_W = 1280
        const val DESIGN_H = 720
    }

    // 마우스 역변환용 — paintComponent 때마다 갱신
    @Volatile private var scale   = 1.0
    @Volatile private var offsetX = 0
    @Volatile private var offsetY = 0

    /** 화면 좌표 → 논리(1280×720) 좌표 변환 */
    fun toLogical(x: Int, y: Int): java.awt.Point = java.awt.Point(
        ((x - offsetX) / scale).toInt(),
        ((y - offsetY) / scale).toInt()
    )

    init {
        isOpaque = true
        isFocusable = true
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val g2d = g as Graphics2D

        // 0. 레터박스/필러박스 영역 검정
        g2d.color = Color.BLACK
        g2d.fillRect(0, 0, width, height)

        // 비율 유지 스케일 계산
        val s  = minOf(width.toDouble() / DESIGN_W, height.toDouble() / DESIGN_H)
        val dw = (DESIGN_W * s).toInt()
        val dh = (DESIGN_H * s).toInt()
        val ox = (width  - dw) / 2
        val oy = (height - dh) / 2
        scale   = s
        offsetX = ox
        offsetY = oy

        // 현재 State가 자체적으로 배경을 처리하는지 확인
        val currentRendersBg = stateManager.currentState?.rendersBackground == true

        // 1. 비디오 프레임 (자체 처리하지 않는 경우만 그림)
        if (!currentRendersBg) {
            val frame = videoBackground?.getCurrentFrame()
            if (frame != null) {
                g2d.drawImage(frame, ox, oy, dw, dh, null)
            } else {
                g2d.color = Color.BLACK
                g2d.fillRect(ox, oy, dw, dh)
            }
        }

        // 2. 렌더링 파이프라인 극한 튜닝 (Graphics2D 커스텀)
        // 안티앨리어싱은 켜서 시각적 품질(계단현상 제거)을 유지하되,
        // 나머지 색상 보간, 알파 블렌딩 등은 SPEED(속도) 위주로 설정하여 CPU 프레셔를 낮춥니다.
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
        g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED)
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_SPEED)
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        // 3. 논리 좌표계(1280×720)로 변환 후 게임 State UI 렌더링
        val saved = g2d.transform
        g2d.translate(ox, oy)
        g2d.scale(s, s)
        g2d.setClip(0, 0, DESIGN_W, DESIGN_H)
        stateManager.render(g2d)
        g2d.transform = saved
    }
}

