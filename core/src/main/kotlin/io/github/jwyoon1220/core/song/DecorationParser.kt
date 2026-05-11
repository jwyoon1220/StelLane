package io.github.jwyoon1220.core.song

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.jwyoon1220.core.data.DecorationData
import java.io.File

object DecorationParser {
    private val mapper = jacksonObjectMapper()

    private const val FILE_NAME = "decoration.json"

    fun parse(file: File): DecorationData =
        mapper.readValue(file, DecorationData::class.java)

    /** songDir 안에 decoration.json 이 있으면 파싱, 없거나 오류면 null 반환. */
    fun parseOrNull(songDir: File): DecorationData? {
        val file = File(songDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { parse(file) }.getOrNull()
    }

    /** decoration.json 으로 저장 */
    fun serialize(data: DecorationData, songDir: File) {
        songDir.mkdirs()
        mapper.writerWithDefaultPrettyPrinter().writeValue(File(songDir, FILE_NAME), data)
    }
}
