package io.github.jwyoon1220.engine

import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.exp
import kotlin.random.Random

/**
 * 합성 하이햇 타격음을 재생합니다.
 *
 * 하이햇 = 고주파 화이트 노이즈를 짧게 버스트 + 빠른 감쇠 엔벨로프.
 * 매번 새 SourceDataLine을 열어 동시 타격에도 중첩 재생 가능합니다.
 * 재생은 전용 데몬 풀에서 비동기 처리합니다.
 */
object HitSound {

    private val SAMPLE_RATE = 44100f
    private val CHANNELS    = 1
    private val SAMPLE_BITS = 16
    private val DURATION_MS = 55       // 타격음 길이 (ms)
    private val VOLUME      = 0.28f    // 0.0–1.0

    private val format = AudioFormat(SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, false)
    private val pool   = Executors.newCachedThreadPool { r ->
        Thread(r, "HitSound").also { it.isDaemon = true; it.priority = Thread.MAX_PRIORITY }
    }

    /** 하이햇 사운드를 비동기로 재생합니다. EDT나 GameLoopThread에서 호출해도 안전합니다. */
    fun play() {
        pool.submit {
            runCatching {
                val line = AudioSystem.getSourceDataLine(format)
                line.open(format, 2048)
                line.start()

                val totalSamples = (SAMPLE_RATE * DURATION_MS / 1000).toInt()
                val buf = ByteArray(totalSamples * 2)  // 16-bit = 2 bytes/sample

                for (i in 0 until totalSamples) {
                    // 화이트 노이즈 + 지수 감쇠 엔벨로프
                    val t         = i.toFloat() / SAMPLE_RATE
                    val envelope  = exp(-t * 80f)          // 빠른 감쇠 (~80 /s)
                    val noise     = (Random.nextFloat() * 2f - 1f)
                    val sample    = (noise * envelope * VOLUME * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                    val byteIdx   = i * 2
                    buf[byteIdx]     = (sample.toInt() and 0xFF).toByte()
                    buf[byteIdx + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
                }

                line.write(buf, 0, buf.size)
                line.drain()
                line.close()
            }
        }
    }
}
