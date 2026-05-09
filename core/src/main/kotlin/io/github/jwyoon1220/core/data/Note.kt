package io.github.jwyoon1220.core.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Note(
    val time: Long = 0L,
    val lane: Int = 0,
    val type: NoteType = NoteType.SHORT,
    val endTime: Long? = null
)
