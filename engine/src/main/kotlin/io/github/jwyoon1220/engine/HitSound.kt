package io.github.jwyoon1220.engine

import org.slf4j.LoggerFactory
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import kotlin.math.exp
import kotlin.random.Random

/**
 * 합성 하이햇 타격음을 재생합니다.
 *
 * (최적화) 런타임 할당 및 연산을 완전히 제거:
 * 1. 클래스 로드 시 딱 1번만 오디오 버퍼(ByteArray)를 연산하여 메모리에 캐싱합니다.
 * 2. 동시 타격(Polyphony)을 지원하기 위해 16개의 Clip 객체를 풀(Pool)로 만들어 둡니다.
 * 3. play() 호출 시 스레드 생성 없이 라운드 로빈 방식으로 즉시 재생합니다. (Zero-Latency)
 */
object HitSound {

    private val log = LoggerFactory.getLogger(HitSound::class.java)

    private const val SAMPLE_RATE = 44100f
    private const val CHANNELS    = 1
    private const val SAMPLE_BITS = 16
    private const val DURATION_MS = 55       // 타격음 길이 (ms)
    private const val VOLUME      = 0.28f    // 0.0–1.0
    private const val POLYPHONY   = 16       // 동시 재생 가능한 최대 음수

    private val format = AudioFormat(SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, false)
    private val clips  = Array<Clip?>(POLYPHONY) { null }
    private var clipIndex = 0

    init {
        try {
            // 1. 딱 1번만 음원 버퍼 계산
            val totalSamples = (SAMPLE_RATE * DURATION_MS / 1000).toInt()
            val buf = ByteArray(totalSamples * 2)

            for (i in 0 until totalSamples) {
                val t         = i.toFloat() / SAMPLE_RATE
                val envelope  = exp(-t * 80f)
                val noise     = (Random.nextFloat() * 2f - 1f)
                val sample    = (noise * envelope * VOLUME * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                val byteIdx   = i * 2
                buf[byteIdx]     = (sample.toInt() and 0xFF).toByte()
                buf[byteIdx + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
            }

            // 2. 풀링할 Clip 16개 메모리에 로드
            val info = DataLine.Info(Clip::class.java, format)
            for (i in 0 until POLYPHONY) {
                val clip = AudioSystem.getLine(info) as Clip
                clip.open(format, buf, 0, buf.size)
                clips[i] = clip
            }
            log.info("[HitSound] {} 클립 초기화 완료", POLYPHONY)
        } catch (e: Exception) {
            log.warn("[HitSound] 초기화 실패 — 타격음이 비활성화됩니다: {}", e.message)
        }
    }

    /** 하이햇 사운드를 재생합니다. 할당, 스레드 풀 오버헤드 없이 네이티브 오디오 버퍼로 즉각 전송됩니다. */
    fun play() {
        val clip = clips[clipIndex]
        if (clip != null) {
            clip.framePosition = 0 // 재생 위치 초기화
            clip.start()
        }
        clipIndex = (clipIndex + 1) % POLYPHONY
    }
}
