package io.github.jwyoon1220.core.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Chart(
    val offsetMs: Long = 0L,
    val notes: List<Note> = emptyList()
)
