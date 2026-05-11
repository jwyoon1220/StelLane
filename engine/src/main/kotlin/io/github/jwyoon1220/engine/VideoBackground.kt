package io.github.jwyoon1220.engine

import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
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

    // VLC 디코드 스레드가 쓰고, EDT가 읽는다. BufferedImage를 재사용해 per-frame alloc 제거.
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
        /**
         * 팩토리 레벨 VLC 옵션.
         * --avcodec-hw=none : 하드웨어 디코더(D3D11VA/DXVA2)를 전역 비활성화.
         *   HW 디코더를 사용하면 GPU 메모리 프레임을 CPU 콜백 버퍼로 넘기기 위해
         *   VLC 내부 필터 체인이 재귀 변환을 시도하다 한계(3)에 걸림.
         * --quiet : VLC 3.x + AV1(dav1d) + BT.709 소스 조합에서 색공간 변환 필터
         *   체인 재귀 재시도 메시지("Too high level of recursion")가 반복 출력되는
         *   노이즈를 억제. 재시도 후 I420 fallback 경로로 정상 재생됨.
         */
        private val FACTORY_OPTIONS = arrayOf(
            "--avcodec-hw=none",
            "--no-video-title-show",
            "--no-sub-autodetect-file",  // 자막 자동 로드 방지
            "--no-spu",                   // 자막/OSD 모듈 비활성화
            "--quiet"                    // 내부 필터 체인 재시도 메시지 무음 (비치명적)
        )

        fun create(): VideoBackground {
            return try {
                val factory = MediaPlayerFactory(*FACTORY_OPTIONS)
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
                /**
                 * I420(YUV 4:2:0) 포맷으로 직접 요청.
                 * - dav1d(AV1), avcodec(H.264/H.265) 등 소프트웨어 디코더의 네이티브 출력 포맷.
                 * - VLC 내부 색공간·포맷 변환 필터 체인이 전혀 필요 없어
                 *   "chain filter error: Too high level of recursion" 오류가 발생하지 않음.
                 * - 최대 1920×1080으로 제한해 Java 측 YUV→ARGB 변환 비용을 관리.
                 *   (I420→I420 스케일은 단일 swscale 필터로 재귀 없음)
                 */
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    // I420(YUV 4:2:0)을 소스 해상도 그대로 요청.
                    // dav1d(AV1)/avcodec(H.264) 소프트웨어 디코더는 I420을
                    // 네이티브 출력 포맷으로 처음부터 사용하므로
                    // VLC 내부 변환 필터 체인이 전혀 필요 없음 → 재귀 오류 발생 안 함.
                    // Java 측 스케일링은 RenderPanel.paintComponent의 drawImage가 담당.
                    return I420Format(sourceWidth, sourceHeight)
                }
                override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
            },
            object : RenderCallback {
                // 해상도 변경 시에만 재할당 — per-frame 힙 할당 없음
                private var cachedBitmap: BufferedImage? = null
                private var cachedPixels: IntArray? = null
                private var yBytes: ByteArray? = null
                private var uBytes: ByteArray? = null
                private var vBytes: ByteArray? = null
                private var cachedW = 0
                private var cachedH = 0

                override fun display(mp: MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: BufferFormat) {
                    val w = bufferFormat.width
                    val h = bufferFormat.height
                    if (w <= 0 || h <= 0) return

                    if (cachedBitmap == null || cachedW != w || cachedH != h) {
                        cachedBitmap = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                        cachedPixels = (cachedBitmap!!.raster.dataBuffer as DataBufferInt).data
                        yBytes = ByteArray(w * h)
                        uBytes = ByteArray((w ushr 1) * (h ushr 1))
                        vBytes = ByteArray((w ushr 1) * (h ushr 1))
                        cachedW = w
                        cachedH = h
                    }

                    val uvSize = (w ushr 1) * (h ushr 1)
                    nativeBuffers[0].duplicate().get(yBytes!!, 0, w * h)
                    nativeBuffers[1].duplicate().get(uBytes!!, 0, uvSize)
                    nativeBuffers[2].duplicate().get(vBytes!!, 0, uvSize)

                    convertI420toArgb(yBytes!!, uBytes!!, vBytes!!, cachedPixels!!, w, h)
                    currentFrame = cachedBitmap
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

    /**
     * VLC 네이티브 로그 핸들을 생성합니다. 주로 테스트에서 에러 감지용으로 사용.
     * 반환값은 사용 후 반드시 [uk.co.caprica.vlcj.log.NativeLog.release]로 해제해야 합니다.
     */
    fun createNativeLog() = factory?.application()?.newLog()

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
        // ":avcodec-hw=none" — 미디어별 소프트웨어 디코딩 강제 (팩토리 옵션과 이중 보호).
        // 하드웨어 디코더(D3D11VA 등)가 켜지면 GPU 메모리 프레임을 콜백 서피스(CPU)로
        // 복사하는 과정에서 VLC 필터 체인 재귀 오류가 발생하므로 반드시 비활성화.
        vlcExecutor.submit { mediaPlayer?.media()?.play(path, ":avcodec-hw=none") }
    }

    /** 재생하지 않고 미디어만 파싱 및 로드합니다. */
    fun prepare(path: String) {
        log.info("[VLC] prepare 요청: {}", path)
        timeAnchor = TimeAnchor(0L, System.nanoTime())
        vlcExecutor.submit { mediaPlayer?.media()?.prepare(path, ":avcodec-hw=none") }
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
        //log.debug("[VLC] seek 요청: {}ms", ms)
        timeAnchor = TimeAnchor(ms, System.nanoTime())
        vlcExecutor.submit { mediaPlayer?.controls()?.setTime(ms) }
    }

    /** 현재 재생 위치(ms). 미디어가 없으면 0 반환. */
    fun getTimeMs(): Long = mediaPlayer?.status()?.time()?.let { maxOf(0L, it) } ?: 0L

    /** 미디어의 총 길이(ms). 알 수 없으면 0 반환. */
    fun getLengthMs(): Long = mediaPlayer?.status()?.length()?.let { maxOf(0L, it) } ?: 0L

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

// ────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ────────────────────────────────────────────────────────────────────────────

/**
 * I420 (YUV 4:2:0) 콜백 버퍼 포맷.
 * - 평면 3개: Y(w×h), U(w/2 × h/2), V(w/2 × h/2)
 * - pitches = 각 평면의 줄당 바이트 수 (= 평면 너비)
 * - lines   = 각 평면의 줄 수 (= 평면 높이)
 */
private class I420Format(width: Int, height: Int) : BufferFormat(
    "I420", width, height,
    intArrayOf(width, width ushr 1, width ushr 1),
    intArrayOf(height, height ushr 1, height ushr 1)
)

/**
 * BT.601 YUV420(I420) → TYPE_INT_ARGB 변환.
 *
 * VLC 디코드 스레드에서 호출되며 per-frame 힙 할당 없음(프리-얼로케이티드 배열 재사용).
 * 공식 (ITU-R BT.601, 정수 근사):
 *   R = clamp((298·(Y-16) + 409·(V-128) + 128) >> 8, 0, 255)
 *   G = clamp((298·(Y-16) - 100·(U-128) - 208·(V-128) + 128) >> 8, 0, 255)
 *   B = clamp((298·(Y-16) + 516·(U-128) + 128) >> 8, 0, 255)
 */
private fun convertI420toArgb(
    y: ByteArray, u: ByteArray, v: ByteArray,
    argb: IntArray, w: Int, h: Int
) {
    val uvStride = w ushr 1
    var idx = 0
    for (row in 0 until h) {
        val uvOff = (row ushr 1) * uvStride
        for (col in 0 until w) {
            val yy = (y[idx].toInt() and 0xFF) - 16
            val uu = (u[uvOff + (col ushr 1)].toInt() and 0xFF) - 128
            val vv = (v[uvOff + (col ushr 1)].toInt() and 0xFF) - 128

            val c = 298 * yy + 128
            val r = ((c + 409 * vv) shr 8).coerceIn(0, 255)
            val g = ((c - 100 * uu - 208 * vv) shr 8).coerceIn(0, 255)
            val b = ((c + 516 * uu) shr 8).coerceIn(0, 255)

            argb[idx++] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }
}

