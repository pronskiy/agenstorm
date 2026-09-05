package com.pronskiy.agenstorm.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step D1.5: normalising raw model output into a subject + body commit message. */
class MessagePostProcessorTest {

    @Test
    fun stripsCodeFencesWithAndWithoutLanguageTag() {
        assertEquals("feat: x\n\nBody.", MessagePostProcessor.process("```\nfeat: x\n\nBody.\n```"))
        assertEquals("feat: x\n\nBody.", MessagePostProcessor.process("```text\nfeat: x\n\nBody.\n```\n"))
    }

    @Test
    fun stripsChattyPrefixes() {
        assertEquals("feat: x", MessagePostProcessor.process("Commit message:\nfeat: x"))
        assertEquals("feat: x\n\nBody", MessagePostProcessor.process("Here is the commit message:\n\nfeat: x\n\nBody"))
        assertEquals("feat: x", MessagePostProcessor.process("Here's a suggested commit message:\n\nfeat: x"))
        assertEquals("feat: x", MessagePostProcessor.process("Commit message: feat: x"))
    }

    @Test
    fun stripsQuotesAroundASingleLineMessage() {
        assertEquals("feat: x", MessagePostProcessor.process("\"feat: x\""))
        assertEquals("feat: x", MessagePostProcessor.process("`feat: x`"))
    }

    @Test
    fun ensuresExactlyOneBlankLineBetweenSubjectAndBody() {
        assertEquals("feat: x\n\nBody starts here", MessagePostProcessor.process("feat: x\nBody starts here"))
        assertEquals("feat: x\n\nBody", MessagePostProcessor.process("feat: x\n\n\n\nBody"))
        assertEquals("feat: x\n\nLine one\nLine two", MessagePostProcessor.process("feat: x\n\nLine one\nLine two\n\n\n"))
    }

    @Test
    fun wrapsLongBodyLinesAtWordBoundaries() {
        val longLine = "This body line is deliberately made long enough to exceed the seventy-two column limit twice over so it wraps."
        val result = MessagePostProcessor.process("feat: x\n\n$longLine")
        val body = result.lines().drop(2)
        assertTrue(body.size >= 2)
        body.forEach { assertTrue("'$it' is ${it.length} chars", it.length <= 72) }
        assertEquals(longLine, body.joinToString(" "))
    }

    @Test
    fun leavesUnbreakableTokensAndListIndentationAlone() {
        val url = "https://example.com/" + "a".repeat(80)
        val result = MessagePostProcessor.process("feat: x\n\nSee $url\n- item one\n- item two")
        assertEquals("feat: x\n\nSee\n$url\n- item one\n- item two", result)
    }

    @Test
    fun leavesAnOverlongSubjectForThePlatformInspection() {
        val subject = "feat: " + "x".repeat(90)
        assertEquals(subject, MessagePostProcessor.process("$subject\n"))
    }

    @Test
    fun bodyDisabledKeepsOnlyTheFirstParagraph() {
        assertEquals("feat: x", MessagePostProcessor.process("feat: x\n\nBody one.\n\nBody two.", bodyEnabled = false))
        assertEquals("feat: x", MessagePostProcessor.process("feat: x\nsecond subject line?\n\nBody", bodyEnabled = false).lines().first())
    }

    @Test
    fun normalisesLineEndingsAndTrailingWhitespace() {
        assertEquals("feat: x\n\nBody", MessagePostProcessor.process("feat: x   \r\n\r\nBody  \r\n"))
        assertEquals("", MessagePostProcessor.process("   \n\n"))
    }
}
