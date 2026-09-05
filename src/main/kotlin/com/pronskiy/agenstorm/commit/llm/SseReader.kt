package com.pronskiy.agenstorm.commit.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile

/** One server-sent event: the optional `event:` name and the `data:` payload (multi-line data joined with `\n`). */
data class SseEvent(val event: String?, val data: String)

/**
 * Turns raw response lines into [SseEvent]s: `data:` lines accumulate, `event:` names the event, a blank line
 * dispatches, comments (`:`), `id:` and `retry:` are ignored, a trailing event without a blank line is still
 * dispatched, and a `[DONE]` payload ends the stream. Events without data are dropped.
 */
object SseReader {

    const val DONE = "[DONE]"

    fun parse(lines: Flow<String>): Flow<SseEvent> = flow {
        var eventName: String? = null
        val data = ArrayList<String>()
        var done = false

        suspend fun dispatch() {
            if (data.isEmpty()) {
                eventName = null
                return
            }
            val payload = data.joinToString("\n")
            val name = eventName
            data.clear()
            eventName = null
            if (payload == DONE) {
                done = true
            } else {
                emit(SseEvent(name, payload))
            }
        }

        lines.takeWhile { !done }.collect { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isEmpty() -> dispatch()
                line.startsWith(":") -> Unit
                else -> {
                    val colon = line.indexOf(':')
                    val field = if (colon < 0) line else line.substring(0, colon)
                    val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
                    when (field) {
                        "event" -> eventName = value
                        "data" -> data += value
                        else -> Unit
                    }
                }
            }
        }
        if (!done) dispatch()
    }
}
