package com.pronskiy.agenstorm.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step D1.3: plain `{var}` substitution over the default and custom prompt templates. */
class PromptBuilderTest {

    private val context = PromptContext(
        diff = "diff --git a/src/Foo.php b/src/Foo.php\n+echo 1;",
        stat = "M src/Foo.php (+1 -0)",
        branch = "feature/x",
        hint = "mention the config change",
        language = "",
        conventionalCommits = true,
    )

    @Test
    fun defaultTemplatesComeFromResources() {
        assertTrue(PromptBuilder.DEFAULT_SYSTEM.contains("at most 72 characters"))
        assertTrue(PromptBuilder.DEFAULT_SYSTEM.contains("{conventional}"))
        assertTrue(PromptBuilder.DEFAULT_SYSTEM.contains("{language}"))
        assertTrue(PromptBuilder.DEFAULT_USER.contains("{diff}"))
        assertTrue(PromptBuilder.DEFAULT_USER.contains("{stat}"))
    }

    @Test
    fun systemPromptExpandsConventionalAndLanguageFlags() {
        val builder = PromptBuilder()

        val plain = builder.buildSystem(context.copy(conventionalCommits = false, language = ""))
        assertFalse(plain.contains("Conventional Commits"))
        assertFalse(plain.contains("Write in"))
        assertFalse(plain.contains("{"))
        assertFalse(plain.endsWith(" "))

        val conventional = builder.buildSystem(context.copy(conventionalCommits = true, language = "German"))
        assertTrue(conventional.contains(PromptBuilder.CONVENTIONAL_TEXT))
        assertTrue(conventional.contains("Write in German."))
        assertFalse(conventional.contains("  "))
    }

    @Test
    fun userPromptCarriesBranchHintStatAndDiff() {
        val user = PromptBuilder().buildUser(context)
        assertEquals(
            "Branch: feature/x\n\nHint from the author (follow it if present): mention the config change\n\nFiles:\nM src/Foo.php (+1 -0)\n\nDiff:\ndiff --git a/src/Foo.php b/src/Foo.php\n+echo 1;",
            user,
        )
    }

    @Test
    fun emptyHintAndBranchGetExplicitPlaceholders() {
        val user = PromptBuilder().buildUser(context.copy(branch = "", hint = "   "))
        assertTrue(user.startsWith("Branch: (unknown)\n"))
        assertTrue(user.contains("(follow it if present): (none)\n"))
    }

    @Test
    fun customTemplatesAreSubstitutedAndUnknownPlaceholdersKept() {
        val builder = PromptBuilder(systemTemplate = "S {language} {nope}", userTemplate = "U {hint} on {branch}")
        assertEquals("S Write in French. {nope}", builder.buildSystem(context.copy(language = "French")))
        assertEquals("U mention the config change on feature/x", builder.buildUser(context))
    }

    @Test
    fun buildProducesARequestWithModelAndTokens() {
        val request = PromptBuilder().build(context, model = "claude-sonnet-5", maxTokens = 512)
        assertEquals("claude-sonnet-5", request.model)
        assertEquals(512, request.maxTokens)
        assertEquals(PromptBuilder().buildSystem(context), request.system)
        assertEquals(PromptBuilder().buildUser(context), request.user)
    }
}
