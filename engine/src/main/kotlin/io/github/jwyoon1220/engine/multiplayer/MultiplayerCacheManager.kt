package io.github.jwyoon1220.engine.multiplayer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

object MultiplayerCacheManager {

    private val log = LoggerFactory.getLogger(MultiplayerCacheManager::class.java)
    private val mapper = jacksonObjectMapper()

    private val cacheDir: Path = Path(System.getProperty("user.home"), ".stellane", "cache")
    private val indexFile: Path = cacheDir.resolve("index.json")

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CacheEntry(
        val sha256: String = "",
        val fileName: String = "",
        var lastUsedMs: Long = 0L,
        val sizeBytes: Long = 0L
    )

    private val index = Object2ObjectOpenHashMap<String, CacheEntry>()

    init {
        cacheDir.createDirectories()
        loadIndex()
    }

    private fun loadIndex() {
        if (!indexFile.exists()) return
        runCatching {
            val list: List<CacheEntry> = mapper.readValue(indexFile.toFile())
            list.forEach { index[it.sha256] = it }
        }.onFailure { log.warn("캐시 인덱스 로드 실패: {}", it.message) }
    }

    private fun saveIndex() {
        runCatching {
            mapper.writeValue(indexFile.toFile(), index.values.toList())
        }.onFailure { log.warn("캐시 인덱스 저장 실패: {}", it.message) }
    }

    /**
     * sha256 이 캐시에 존재하면 Path 반환, 없으면 null.
     * lastUsedMs는 메모리에만 갱신 — putCache/cleanExpired 때 일괄 저장.
     */
    fun getCachedPath(sha256: String): Path? {
        val entry = index[sha256] ?: return null
        val path = cacheDir.resolve(entry.fileName)
        if (!path.exists()) { index.remove(sha256); saveIndex(); return null }
        entry.lastUsedMs = System.currentTimeMillis()
        return path
    }

    /**
     * 스트림에서 청크를 읽어 캐시에 저장. sha256 검증 후 완료 Path 반환.
     * 호출자(네트워크 스레드)는 전체 파일이 도착한 후 이 함수를 호출해야 함.
     */
    fun putCache(sha256: String, originalName: String, data: InputStream, sizeBytes: Long): Path {
        val safe = originalName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
        val fileName = "${sha256.take(8)}_$safe"
        val dest = cacheDir.resolve(fileName)

        Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
            data.copyTo(out, bufferSize = 256 * 1024)
        }

        val actualSha = sha256File(dest)
        if (actualSha != sha256) {
            dest.deleteIfExists()
            error("캐시 파일 sha256 불일치: expected=$sha256 actual=$actualSha")
        }

        val entry = CacheEntry(sha256, fileName, System.currentTimeMillis(), sizeBytes)
        index[sha256] = entry
        saveIndex()
        log.info("캐시 저장: {} ({} bytes)", fileName, sizeBytes)
        return dest
    }

    /** maxAgeDays 이상 미사용 항목 삭제. 앱 시작 시 백그라운드 호출. */
    fun cleanExpired(maxAgeDays: Int = 7) {
        val cutoff = System.currentTimeMillis() - maxAgeDays.toLong() * 86_400_000L
        val toRemove = index.values.filter { it.lastUsedMs < cutoff }
        toRemove.forEach { entry ->
            cacheDir.resolve(entry.fileName).deleteIfExists()
            index.remove(entry.sha256)
            log.info("캐시 만료 삭제: {}", entry.fileName)
        }
        if (toRemove.isNotEmpty()) saveIndex()
    }

    private fun sha256File(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(256 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256OfFile(path: Path): String = sha256File(path)
}
