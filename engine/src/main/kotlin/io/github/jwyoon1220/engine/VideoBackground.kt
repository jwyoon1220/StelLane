package io.github.jwyoon1220.engine

import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import org.lwjgl.nanovg.NanoVGGL3.nvglCreateImageFromHandle
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_BGRA
import org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.system.MemoryUtil
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

    // 디커플링 렌더링을 위한 프레임 갱신 식별자
    @Volatile private var frameId: Long = 0L

    /** RenderPanel.paintComponent 에서 최신 프레임을 가져갑니다. */
    fun getCurrentFrame(): BufferedImage? = currentFrame
    
    /** 최신 프레임의 고유 ID. 캐시 갱신 여부를 판단할 때 사용합니다. */
    fun getFrameId(): Long = frameId

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

    // VLC playing/paused/stopped/finished 이벤트로 유지되는 재생 상태 캐시.
    // getSmoothTimeMs/Double 에서 JNI player.status().isPlaying 폴링을 대체합니다.
    @Volatile private var isCurrentlyPlaying = false

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
                    // VLC가 내부적으로 RV32(ARGB)로 변환하여 넘겨주도록 요청합니다.
                    return RV32BufferFormat(sourceWidth, sourceHeight)
                }
                override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
            },
            object : RenderCallback {
                // 핑-퐁 더블 버퍼: VLC 디코드 스레드가 한쪽을 쓰는 동안
                // 게임 루프가 다른 쪽을 안전하게 읽을 수 있도록 합니다.
                // (단일 버퍼 시 VLC 쓰기와 게임 루프 drawImage가 동일 픽셀 배열에
                //  동시 접근하여 영상 찢김이 발생하는 데이터 레이스 방지)
                private var frameBufA: BufferedImage? = null
                private var frameBufB: BufferedImage? = null
                private var pixelsA: IntArray? = null
                private var pixelsB: IntArray? = null
                private var cachedW = 0
                private var cachedH = 0
                private var writeToA = true  // 현재 VLC가 쓰는 버퍼 선택자

                override fun display(mp: MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: BufferFormat) {
                    val w = bufferFormat.width
                    val h = bufferFormat.height
                    if (w <= 0 || h <= 0) return

                    if (frameBufA == null || cachedW != w || cachedH != h) {
                        frameBufA = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                        frameBufB = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                        pixelsA = (frameBufA!!.raster.dataBuffer as DataBufferInt).data
                        pixelsB = (frameBufB!!.raster.dataBuffer as DataBufferInt).data
                        cachedW = w
                        cachedH = h
                    }

                    // 비활성 버퍼에 픽셀 복사 후 currentFrame을 volatile 로 교체
                    // Java Memory Model: volatile 쓰기 이전의 모든 픽셀 쓰기가
                    // volatile 읽기 이후의 코드에서 가시적임이 보장됩니다.
                    if (writeToA) {
                        nativeBuffers[0].asIntBuffer().get(pixelsA!!)
                        currentFrame = frameBufA
                    } else {
                        nativeBuffers[0].asIntBuffer().get(pixelsB!!)
                        currentFrame = frameBufB
                    }
                    writeToA = !writeToA
                    frameId++
                    frameReady = true
                }
            },
            true
        )
        player.videoSurface().set(surface)

        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) {
                log.debug("[VLC] playing 이벤트 수신")
                isCurrentlyPlaying = true
                onPlayingStarted?.invoke()
            }
            override fun paused(mp: MediaPlayer)   { log.debug("[VLC] paused");   isCurrentlyPlaying = false }
            override fun stopped(mp: MediaPlayer)  { log.debug("[VLC] stopped");  isCurrentlyPlaying = false }
            override fun finished(mp: MediaPlayer) { log.debug("[VLC] finished"); isCurrentlyPlaying = false; onFinished?.invoke() }
            override fun error(mp: MediaPlayer)    { log.warn("[VLC] 미디어 오류 발생"); isCurrentlyPlaying = false }
            // VLC가 내부 클록을 갱신할 때마다 호출 (디코딩 주기, 보통 ~30fps).
            // 이 이벤트로 timeAnchor를 갱신하면 getSmoothTimeMs/Double이
            // 게임 루프 핫패스에서 JNI를 전혀 호출하지 않아도 됩니다.
            override fun timeChanged(mp: MediaPlayer, newTime: Long) {
                if (newTime >= 0) timeAnchor = TimeAnchor(newTime, System.nanoTime())
            }
        })

        log.info("[VideoBackground] 초기화 완료 (isAvailable=true)")
    }
    /**
     * VLC 네이티브 로그 핸들을 생성합니다. 주로 테스트에서 에러 감지용으로 사용.
     * 반환값은 사용 후 반드시 [uk.co.caprica.vlcj.log.NativeLog.release]로 해제해야 합니다.
     */
    fun createNativeLog() = factory?.application()?.newLog()

    /**
     * 현재 재생 위치를 밀리초로 반환합니다. JNI 호출 없음.
     *
     * timeAnchor는 VLC의 timeChanged 이벤트(VLC 내부 스레드)가 갱신하고,
     * isCurrentlyPlaying은 playing/paused/stopped 이벤트가 갱신합니다.
     * 두 값 모두 @Volatile이므로 게임 루프 스레드에서 안전하게 읽을 수 있습니다.
     */
    fun getSmoothTimeMs(): Long {
        val anchor = timeAnchor
        if (anchor.vlcMs < 0) return 0L
        if (!isCurrentlyPlaying) return anchor.vlcMs
        return anchor.vlcMs + (System.nanoTime() - anchor.nanoTime) / 1_000_000L
    }

    /**
     * [getSmoothTimeMs]와 동일하지만 나노초 보간을 서브-밀리초(Double) 정밀도로 반환합니다.
     * 렌더링에서 노트 위치 계산 시 사용하면 정수 ms 양자화로 인한 뚝뚝 끊김을 방지합니다.
     */
    fun getSmoothTimeDouble(): Double {
        val anchor = timeAnchor
        if (!isCurrentlyPlaying) return anchor.vlcMs.toDouble()
        return anchor.vlcMs + (System.nanoTime() - anchor.nanoTime) / 1_000_000.0
    }

    fun play(path: String) {
        log.info("[VLC] play 요청: {}", path)
        isCurrentlyPlaying = false  // playing 이벤트 수신 전까지 보간 억제
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

    // ── GL 텍스처 레이어 ─────────────────────────────────────────────────────
    /** OpenGL 텍스처 ID. initGLTexture() 호출 후 유효합니다. */
    private var glTexId: Int = 0
    /** NanoVG 이미지 핸들 (-1 = 미초기화). */
    private var nvgImgHandle: Int = -1
    /** VLC display 콜백에서 true 로 설정, 메인 스레드에서 uploadPendingFrame() 이 소비합니다. */
    @Volatile private var frameReady = false
    /** 마지막으로 업로드된 텍스처의 폭. glTexImage2D(재할당)과 glTexSubImage2D(갱신)를 구분합니다. */
    private var lastUploadW = 0
    private var lastUploadH = 0
    /** 픽셀 업로드용 재사용 버퍼 (매 프레임 alloc/free 제거). */
    private var uploadBuf: ByteBuffer? = null

    /**
     * OpenGL 컨텍스트 생성 후(메인 스레드에서) 한 번만 호출합니다.
     * GL 텍스처를 생성하고 초기 필터를 설정합니다.
     */
    fun initGLTexture() {
        if (!isAvailable) return
        glTexId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, glTexId)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D, 0)
        log.debug("[VideoBackground] GL 텍스처 생성: id={}", glTexId)
    }

    /**
     * 메인 스레드(렌더 루프)에서 매 프레임 호출합니다.
     * VLC 새 프레임이 있을 때만 GL 텍스처를 업로드합니다.
     *
     * 최적화:
     * - 해상도가 바뀔 때만 glTexImage2D(재할당), 그 외에는 glTexSubImage2D(갱신)로 드라이버 alloc 제거.
     * - ByteBuffer를 프레임마다 새로 할당하지 않고 재사용합니다.
     * - 크기가 변경될 때만 NVG 핸들을 무효화합니다.
     */
    fun uploadPendingFrame() {
        if (!isAvailable || !frameReady) return
        val frame = currentFrame ?: return
        frameReady = false  // consume

        val w = frame.width; val h = frame.height
        if (w <= 0 || h <= 0) return

        // 픽셀 업로드 버퍼 재사용 (크기가 바뀌면 재할당)
        val needed = w * h * 4
        var buf = uploadBuf
        if (buf == null || buf.capacity() < needed) {
            buf?.let { MemoryUtil.memFree(it) }
            buf = MemoryUtil.memAlloc(needed)
            uploadBuf = buf
        }
        buf.clear()
        val pixels = (frame.raster.dataBuffer as DataBufferInt).data
        buf.asIntBuffer().put(pixels)
        buf.limit(needed)

        glBindTexture(GL_TEXTURE_2D, glTexId)
        if (w != lastUploadW || h != lastUploadH) {
            // 해상도 변경 → 텍스처 스토리지 재할당
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buf)
            lastUploadW = w; lastUploadH = h
            // NVG 핸들 무효화 (해상도가 바뀌었으므로)
            if (nvgImgHandle >= 0) { nvgImgHandle = -1 }
        } else {
            // 동일 해상도 → 스토리지 재사용, NVG 핸들 유지
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buf)
        }
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    /**
     * NanoVG 이미지 핸들을 반환합니다. 아직 초기화되지 않았으면 생성합니다.
     * [vg] 는 유효한 NanoVG 컨텍스트여야 합니다.
     * @return NanoVG 이미지 핸들, 비디오가 없으면 -1
     */
    fun getNvgImageHandle(vg: Long): Int {
        if (!isAvailable || glTexId == 0) return -1
        val frame = currentFrame ?: return -1
        if (nvgImgHandle < 0) {
            nvgImgHandle = nvglCreateImageFromHandle(vg, glTexId,
                frame.width, frame.height, 0)
            log.debug("[VideoBackground] NVG 이미지 핸들 생성: {}", nvgImgHandle)
        }
        return nvgImgHandle
    }

    /**
     * 원시 OpenGL 텍스처 ID를 반환합니다. GlQuadBatchRenderer.drawRect의 textureId 파라미터로
     * 직접 사용할 수 있습니다. initGLTexture() 이후 유효합니다.
     */
    fun getGlTextureId(): Int = glTexId

    fun release() {
        log.info("[VideoBackground] release 시작")
        uploadBuf?.let { MemoryUtil.memFree(it); uploadBuf = null }
        vlcExecutor.submit {
            runCatching { mediaPlayer?.controls()?.stop() }
            runCatching { mediaPlayer?.release() }
            runCatching { factory?.release() }
            log.info("[VideoBackground] release 완료")
        }
        vlcExecutor.shutdown()
        // VLC 네이티브 스레드가 블로킹할 수 있으므로 최대 2초만 대기
        runCatching { vlcExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) }
    }
}



