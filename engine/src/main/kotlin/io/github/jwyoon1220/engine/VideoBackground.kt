package io.github.jwyoon1220.engine

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * vlcj 콜백 서피스(소프트웨어 디코딩)를 사용해 비디오 프레임을 BufferedImage에 씁니다.
 *
 * AWT Canvas(heavyweight)를 사용하지 않으므로 Swing GlassPane/컴포넌트와 Z-order 충돌 없음.
 * VLC 미설치 환경에서는 isAvailable=false 상태로 동작(영상 없이 게임만 실행).
 */
class VideoBackground private constructor(
    val isAvailable: Boolean,
    private val factory: MediaPlayerFactory?,
    val mediaPlayer: EmbeddedMediaPlayer?
) {
    // VLC 디코드 스레드가 쓰고, EDT가 읽는다. 새 BufferedImage 객체를 @Volatile 참조로 교체.
    @Volatile private var currentFrame: BufferedImage? = null

    // VLC getTime() 보간용 앵커 (VLC 시간 갱신 주기가 ~33ms이므로 nanoTime으로 보간)
    private data class TimeAnchor(val vlcMs: Long, val nanoTime: Long)
    @Volatile private var timeAnchor = TimeAnchor(0L, System.nanoTime())

    companion object {
        fun create(): VideoBackground {
            return try {
                val factory = MediaPlayerFactory()
                val player  = factory.mediaPlayers().newEmbeddedMediaPlayer()
                VideoBackground(isAvailable = true, factory = factory, mediaPlayer = player).also { it.initCallbackSurface() }
            } catch (e: Exception) {
                System.err.println("[VideoBackground] VLC를 초기화하지 못했습니다: ${e.message}")
                VideoBackground(isAvailable = false, factory = null, mediaPlayer = null)
            }
        }
    }

    private fun initCallbackSurface() {
        val fac    = factory    ?: return
        val player = mediaPlayer ?: return

        val surface = fac.videoSurfaces().newVideoSurface(
            object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int) =
                    RV32BufferFormat(sourceWidth, sourceHeight)

                override fun allocatedBuffers(buffers: Array<ByteBuffer>) { /* 사전 할당 불필요 */ }
            },
            object : RenderCallback {
                // VLC "RV32" = little-endian BGRA → Java TYPE_INT_ARGB(0xAARRGGBB) 일치
                override fun display(mp: MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat) {
                    val w = bufferFormat.width
                    val h = bufferFormat.height
                    if (w <= 0 || h <= 0) return

                    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                    val dst = (img.raster.dataBuffer as DataBufferInt).data
                    nativeBuffers[0].duplicate()
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asIntBuffer()
                        .get(dst, 0, w * h)

                    currentFrame = img   // @Volatile 원자 쓰기
                }
            },
            true
        )
        player.videoSurface().set(surface)
    }

    /** RenderPanel.paintComponent 에서 최신 프레임을 가져갑니다. */
    fun getCurrentFrame(): BufferedImage? = currentFrame

    /**
     * VLC getTime()을 nanoTime으로 보간한 부드러운 재생 위치(ms).
     *
     * VLC 내부 타이머는 ~33ms 단위로 갱신되므로, 마지막 갱신 이후 흐른
     * System.nanoTime() 차이를 더해 프레임마다 연속적인 값을 반환합니다.
     * 미디어가 없거나 정지 중이면 마지막 VLC 시각을 그대로 반환합니다.
     */
    fun getSmoothTimeMs(): Long {
        val player = mediaPlayer ?: return 0L
        val vlcMs  = player.status().time()
        if (vlcMs < 0) return 0L

        val nowNano = System.nanoTime()
        val anchor  = timeAnchor
        return if (vlcMs != anchor.vlcMs) {
            // VLC 타이머가 새 값으로 갱신됨 → 앵커 업데이트
            timeAnchor = TimeAnchor(vlcMs, nowNano)
            vlcMs
        } else if (player.status().isPlaying) {
            // 앵커 이후 흐른 나노초를 밀리초로 변환하여 더함
            vlcMs + (nowNano - anchor.nanoTime) / 1_000_000L
        } else {
            vlcMs
        }
    }

    fun play(path: String) {
        timeAnchor = TimeAnchor(0L, System.nanoTime())
        mediaPlayer?.media()?.play(path)
    }
    fun stop()         { mediaPlayer?.controls()?.stop() }
    fun pause()        { mediaPlayer?.controls()?.pause() }
    fun seek(ms: Long) {
        mediaPlayer?.controls()?.setTime(ms)
        timeAnchor = TimeAnchor(ms, System.nanoTime())
    }

    /** 현재 재생 위치(ms). 미디어가 없으면 0 반환. */
    fun getTimeMs(): Long = mediaPlayer?.status()?.time()?.let { maxOf(0L, it) } ?: 0L

    fun isPlaying(): Boolean = mediaPlayer?.status()?.isPlaying ?: false

    fun release() {
        mediaPlayer?.release()
        factory?.release()
    }
}

