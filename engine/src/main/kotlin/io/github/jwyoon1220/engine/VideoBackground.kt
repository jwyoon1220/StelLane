package io.github.jwyoon1220.engine

import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * vlcj 콜백 서피스(소프트웨어 디코딩)를 사용해 비디오 프레임을 BufferedImage에 씁니다.
 *
 * AWT Canvas(heavyweight)를 사용하지 않으므로 Swing GlassPane/컴포넌트와 Z-order 충돌 없음.
 * VLC 미설치 환경에서는 isAvailable=false 상태로 동작(영상 없이 게임만 실행).
 *
 * 모든 VLC 제어(play/stop/pause/seek)는 전용 단일 스레드 executor에서 비동기 실행됩니다.
 * EDT나 VLC 네이티브 콜백 스레드에서 직접 호출해도 안전합니다.
 */
class VideoBackground private constructor(
    val isAvailable: Boolean,
    private val factory: MediaPlayerFactory?,
    val mediaPlayer: EmbeddedMediaPlayer?
) {
    private val log = LoggerFactory.getLogger(VideoBackground::class.java)

    // VLC 제어 전용 단일 스레드 — EDT/VLC 콜백에서 블로킹 없이 호출 가능
    private val vlcExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VLC-Control").also { it.isDaemon = true }
    }

    // VLC 디코드 스레드가 쓰고, EDT가 읽는다. 새 BufferedImage 객체를 @Volatile 참조로 교체.
    @Volatile private var currentFrame: BufferedImage? = null

    /**
     * VLC가 실제로 재생을 시작했을 때(loading 완료 후) 한 번 호출됩니다.
     * VLC 네이티브 스레드에서 호출되므로 구현체는 thread-safe 해야 합니다.
     */
    @Volatile var onPlayingStarted: (() -> Unit)? = null

    /** 미디어 재생이 끝까지 완료됐을 때 한 번 호출됩니다. */
    @Volatile var onFinished: (() -> Unit)? = null

    // VLC getTime() 보간용 앵커 (VLC 시간 갱신 주기가 ~33ms이므로 nanoTime으로 보간)
    private data class TimeAnchor(val vlcMs: Long, val nanoTime: Long)
    @Volatile private var timeAnchor = TimeAnchor(0L, System.nanoTime())

    companion object {
        fun create(): VideoBackground {
            return try {
                val factory = MediaPlayerFactory()
                val player  = factory.mediaPlayers().newEmbeddedMediaPlayer()
                VideoBackground(isAvailable = true, factory = factory, mediaPlayer = player)
                    .also { it.initCallbackSurface() }
            } catch (e: Exception) {
                LoggerFactory.getLogger(VideoBackground::class.java)
                    .warn("[VideoBackground] VLC 초기화 실패: {}", e.message)
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
                override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
            },
            object : RenderCallback {
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
                    currentFrame = img
                }
            },
            true
        )
        player.videoSurface().set(surface)

        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) {
                log.debug("[VLC] playing 이벤트 수신")
                onPlayingStarted?.invoke()
            }
            override fun paused(mp: MediaPlayer)  { log.debug("[VLC] paused") }
            override fun stopped(mp: MediaPlayer)  { log.debug("[VLC] stopped") }
            override fun finished(mp: MediaPlayer) { log.debug("[VLC] finished"); onFinished?.invoke() }
            override fun error(mp: MediaPlayer)    { log.warn("[VLC] 미디어 오류 발생") }
        })

        log.info("[VideoBackground] 초기화 완료 (isAvailable=true)")
    }

    /** RenderPanel.paintComponent 에서 최신 프레임을 가져갑니다. */
    fun getCurrentFrame(): BufferedImage? = currentFrame

    fun getSmoothTimeMs(): Long {
        val player = mediaPlayer ?: return 0L
        val vlcMs  = player.status().time()
        if (vlcMs < 0) return 0L
        val nowNano = System.nanoTime()
        val anchor  = timeAnchor
        return if (vlcMs != anchor.vlcMs) {
            timeAnchor = TimeAnchor(vlcMs, nowNano)
            vlcMs
        } else if (player.status().isPlaying) {
            vlcMs + (nowNano - anchor.nanoTime) / 1_000_000L
        } else {
            vlcMs
        }
    }

    fun play(path: String) {
        log.info("[VLC] play 요청: {}", path)
        timeAnchor = TimeAnchor(0L, System.nanoTime())
        vlcExecutor.submit { mediaPlayer?.media()?.play(path) }
    }

    /** 일시정지된 미디어를 이어서 재생합니다. */
    fun resume() {
        log.debug("[VLC] resume 요청")
        vlcExecutor.submit { mediaPlayer?.controls()?.play() }
    }

    fun stop() {
        log.debug("[VLC] stop 요청")
        vlcExecutor.submit { mediaPlayer?.controls()?.stop() }
    }

    fun pause() {
        log.debug("[VLC] pause 요청")
        vlcExecutor.submit { mediaPlayer?.controls()?.pause() }
    }

    fun seek(ms: Long) {
        log.debug("[VLC] seek 요청: {}ms", ms)
        timeAnchor = TimeAnchor(ms, System.nanoTime())
        vlcExecutor.submit { mediaPlayer?.controls()?.setTime(ms) }
    }

    /** 현재 재생 위치(ms). 미디어가 없으면 0 반환. */
    fun getTimeMs(): Long = mediaPlayer?.status()?.time()?.let { maxOf(0L, it) } ?: 0L

    fun isPlaying(): Boolean = mediaPlayer?.status()?.isPlaying ?: false

    /** 미디어가 로드되어 재생 가능한 상태이면 true. */
    fun isPlayable(): Boolean = mediaPlayer?.status()?.isPlayable ?: false

    fun release() {
        log.info("[VideoBackground] release 시작")
        vlcExecutor.submit {
            runCatching { mediaPlayer?.controls()?.stop() }
            runCatching { mediaPlayer?.release() }
            runCatching { factory?.release() }
            log.info("[VideoBackground] release 완료")
        }
        vlcExecutor.shutdown()
    }
}

