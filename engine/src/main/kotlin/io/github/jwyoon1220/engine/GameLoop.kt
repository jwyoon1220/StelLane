package io.github.jwyoon1220.engine

import io.github.jwyoon1220.core.StateManager
import java.awt.Color
import java.awt.Graphics2D
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
    private val targetFPS = 144
    private val optimalTime = 1000000000L / targetFPS

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(name = "GameLoopThread") {
            var lastLoopTime = System.nanoTime()

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

                // 3. 목표 FPS 유지를 위한 쓰레드 대기
                val sleepTime = (lastLoopTime - System.nanoTime() + optimalTime) / 1000000
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
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

        // 1. 비디오 프레임을 스케일된 영역에 그리기
        val frame = videoBackground?.getCurrentFrame()
        if (frame != null) {
            g2d.drawImage(frame, ox, oy, dw, dh, null)
        } else {
            g2d.color = Color.BLACK
            g2d.fillRect(ox, oy, dw, dh)
        }

        // 2. 안티앨리어싱
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // 3. 논리 좌표계(1280×720)로 변환 후 게임 State UI 렌더링
        val saved = g2d.transform
        g2d.translate(ox, oy)
        g2d.scale(s, s)
        g2d.setClip(0, 0, DESIGN_W, DESIGN_H)   // clipBounds가 논리 해상도를 반환하도록
        stateManager.render(g2d)
        g2d.transform = saved
    }
}

