package com.pronskiy.agenstorm.links

import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

/** Positive/negative corpus from SPEC.md step A1.1; the Phase A1 "Parser corpus" guardrail. */
class FileLocationParserTest {

    @Test
    fun pathWithLine() {
        assertEquals(
            listOf(FileLocationMatch(TextRange(0, 14), FileLocation("src/Foo.php", 42, null))),
            FileLocationParser.parse("src/Foo.php:42"),
        )
    }

    @Test
    fun pathWithLineAndColumn() {
        assertEquals(
            listOf(FileLocationMatch(TextRange(0, 16), FileLocation("src/Foo.php", 42, 7))),
            FileLocationParser.parse("src/Foo.php:42:7"),
        )
    }

    @Test
    fun relativeAndAbsolutePrefixes() {
        assertEquals(FileLocation("./README.md", 3, null), single("./README.md:3"))
        assertEquals(FileLocation("../docs/README.md", 3, null), single("../docs/README.md:3"))
        assertEquals(FileLocation("/abs/path/x.kt", 1, 1), single("/abs/path/x.kt:1:1"))
    }

    @Test
    fun tokenInsideBackticksAndParentheses() {
        val text = "see `tests/Unit/FooTest.php:12` and (tests/Unit/FooTest.php:12)"
        val matches = FileLocationParser.parse(text)
        assertEquals(2, matches.size)
        matches.forEach { assertEquals(FileLocation("tests/Unit/FooTest.php", 12, null), it.location) }
        assertEquals("tests/Unit/FooTest.php:12", text.substring(matches[0].range.startOffset, matches[0].range.endOffset))
        assertEquals("tests/Unit/FooTest.php:12", text.substring(matches[1].range.startOffset, matches[1].range.endOffset))
    }

    @Test
    fun rangeCoversOnlyTheToken() {
        val text = "x src/Foo.php:42 y"
        assertEquals(listOf(TextRange(2, 16)), FileLocationParser.parse(text).map { it.range })
    }

    @Test
    fun sentencePunctuationAndListsAroundTokens() {
        assertEquals(FileLocation("src/Foo.php", 42, null), single("Fixed in src/Foo.php:42."))
        assertEquals(
            listOf(FileLocation("src/Foo.php", 42, null), FileLocation("src/Bar.php", 7, 1)),
            FileLocationParser.parse("src/Foo.php:42, src/Bar.php:7:1;").map { it.location },
        )
    }

    @Test
    fun directoryLikePathWithoutExtensionNeedsASlash() {
        assertEquals(FileLocation("docs/notes", 12, null), single("docs/notes:12"))
        // Accepted limitation: no extension and no slash is not a location.
        assertEquals(emptyList<FileLocationMatch>(), FileLocationParser.parse("Makefile:3"))
    }

    @Test
    fun dashesAndDotsInsidePathSegments() {
        assertEquals(FileLocation("src/my-lib/foo.bar.php", 10, null), single("src/my-lib/foo.bar.php:10"))
        assertEquals(FileLocation("src/../Foo.php", 1, null), single("src/../Foo.php:1"))
    }

    @Test
    fun negativeCorpusProducesNoMatches() {
        listOf(
            "http://localhost:8080/x",
            "https://example.com/a/b.php:42",
            "see 12:30",
            "App\\Foo::bar()",
            "C:\\Users\\x",
            "foo:bar",
            "10:20:30",
            "v1.2.3:4",
            "Foo.php::42",
            "src/Foo.php:1234567",
        ).forEach { text ->
            assertEquals("expected no match in <$text>", emptyList<FileLocationMatch>(), FileLocationParser.parse(text))
        }
    }

    @Test
    fun lineAndColumnAreOneBased() {
        assertEquals(emptyList<FileLocationMatch>(), FileLocationParser.parse("src/Foo.php:0"))
        assertEquals(FileLocation("src/Foo.php", 3, null), single("src/Foo.php:3:0"))
    }

    @Test
    fun wholeMarkdownDestinationIsOneMatch() {
        assertEquals(
            listOf(FileLocationMatch(TextRange(0, 15), FileLocation("src/Foo.php", 3, 5))),
            FileLocationParser.parse("src/Foo.php:3:5"),
        )
    }

    @Test
    fun emptyAndPlainTextYieldNothing() {
        assertEquals(emptyList<FileLocationMatch>(), FileLocationParser.parse(""))
        assertEquals(emptyList<FileLocationMatch>(), FileLocationParser.parse("no locations here, just words: and colons:"))
    }

    private fun single(text: String): FileLocation {
        val matches = FileLocationParser.parse(text)
        assertEquals("expected exactly one match in <$text>: $matches", 1, matches.size)
        return matches.single().location
    }
}
