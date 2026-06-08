package io.github.jwyoon1220.app

import io.github.jwyoon1220.engine.VideoBackground
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.assertTrue

class VideoDumpTest {

    @Test
    fun testVlcPixelDump() {
        val videoPath = File("../run/songs/STAR_TRAIL/video.mp4").absolutePath
        assertTrue(File(videoPath).exists(), "테스트 비디오 파일이 존재해야 합니다: $videoPath")

        val videoBackground = VideoBackground.create()
        val latch = CountDownLatch(1)

        // VLC가 프레임을 성공적으로 디코딩하여 BufferedImage를 뱉어내는지 백그라운드 스레드로 감시합니다.
        Thread {
            while (true) {
                val frame = videoBackground.getCurrentFrame()
                if (frame != null) {
                    try {
                        val outFile = File("test_dump_output.png")
                        ImageIO.write(frame, "png", outFile)
                        println("픽셀 덤프 성공! 프레임이 다음 경로에 저장되었습니다: ${outFile.absolutePath}")
                        latch.countDown()
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                Thread.sleep(50)
            }
        }.apply { isDaemon = true }.start()

        // EditorState의 환경과 비슷하게 비디오를 prepare(로드) 및 play 합니다.
        videoBackground.play(videoPath)

        // 최대 10초 대기
        val success = latch.await(10, TimeUnit.SECONDS)
        videoBackground.stop()
        videoBackground.release()

        assertTrue(success, "10초 내에 FFmpeg로부터 프레임을 넘겨받지 못했습니다 (화면 렌더링 실패)")
    }
}
