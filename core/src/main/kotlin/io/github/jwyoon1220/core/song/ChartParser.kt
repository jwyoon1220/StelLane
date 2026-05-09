package io.github.jwyoon1220.core.song

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.jwyoon1220.core.data.Chart
import io.github.jwyoon1220.core.data.Song
import java.io.File

object ChartParser {
    private val mapper = jacksonObjectMapper()

    fun parseSong(file: File): Song = mapper.readValue(file, Song::class.java)

    fun parseChart(file: File): Chart = mapper.readValue(file, Chart::class.java)

    fun serializeChart(chart: Chart, file: File) {
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, chart)
    }
}
