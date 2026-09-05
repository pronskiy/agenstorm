package com.pronskiy.agenstorm.commit.llm

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Step D2.4: raw SSE lines → events. */
class SseReaderTest {

    @Test
    fun blankLinesDelimitEventsAndDataLinesAccumulate() = runBlocking {
        val events = SseReader.parse(flowOf("event: message_start", "data: {\"a\":1}", "", "data: line one", "data: line two", "", "data:no-space")).toList()
        assertEquals(
            listOf(SseEvent("message_start", "{\"a\":1}"), SseEvent(null, "line one\nline two"), SseEvent(null, "no-space")),
            events,
        )
    }

    @Test
    fun commentsIdsAndRetriesAreIgnored() = runBlocking {
        val events = SseReader.parse(flowOf(": keep-alive", "id: 42", "retry: 1000", "data: x", "")).toList()
        assertEquals(listOf(SseEvent(null, "x")), events)
    }

    @Test
    fun doneMarkerEndsTheStream() = runBlocking {
        val events = SseReader.parse(flowOf("data: first", "", "data: [DONE]", "", "data: after done", "")).toList()
        assertEquals(listOf(SseEvent(null, "first")), events)
    }

    @Test
    fun emptyDataOnlyEventsAreDroppedAndCarriageReturnsStripped() = runBlocking {
        val events = SseReader.parse(flowOf("event: ping\r", "\r", "data: a\r", "\r")).toList()
        assertEquals(listOf(SseEvent(null, "a")), events)
    }

    @Test
    fun emptyStreamYieldsNothing() = runBlocking {
        assertEquals(emptyList<SseEvent>(), SseReader.parse(flowOf()).toList())
    }
}
