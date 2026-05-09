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
 * VideoBackground 콜백 방식 덕분에 AWT Canvas가 없으므로
 * heavyweight-over-lightweight 오버드로 문제가 발생하지 않습니다.
 */
class RenderPanel(
    private val stateManager: StateManager,
    private val videoBackground: VideoBackground? = null
) : JComponent() {
    init {
        isOpaque = true   // 모든 픽셀을 직접 그리므로 true
        isFocusable = true
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val g2d = g as Graphics2D

        // 1. 비디오 프레임 (없으면 검정 배경)
        val frame = videoBackground?.getCurrentFrame()
        if (frame != null) {
            g2d.drawImage(frame, 0, 0, width, height, null)
        } else {
            g2d.color = Color.BLACK
            g2d.fillRect(0, 0, width, height)
        }

        // 2. 안티앨리어싱
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // 3. 게임 State UI (비디오 위에 오버레이)
        stateManager.render(g2d)
    }
}

