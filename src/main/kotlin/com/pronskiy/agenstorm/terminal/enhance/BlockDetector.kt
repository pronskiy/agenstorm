package com.pronskiy.agenstorm.terminal.enhance

import com.intellij.openapi.util.TextRange
import java.util.regex.Matcher

/** One block a rule recognised: where it is, what the fold says, and — for the viewer — its text. */
data class EnhancedBlock(
    val ruleId: String,
    /** From the first line's start to the last line's end, without the trailing newline. */
    val range: TextRange,
    val summary: String,
    val render: RenderMode,
    /** The block's text for [RenderMode.TREE] and [RenderMode.JSON] rules; null for a plain fold. */
    val payload: String?,
)

/**
 * What one scan found, and where the next one should start: the start of a block whose end has not arrived
 * yet, or the end of the text. A later call with `from = resumeFrom` sees that block whole once it is closed.
 */
data class Detection(val blocks: List<EnhancedBlock>, val resumeFrom: Int)

/**
 * Step I1.2. Text and rules in, blocks out. Line by line: the first enabled rule whose `start` matches a line
 * opens a block; a rule with an `end` runs to the first later line `end` matches, within `maxLines`, and a
 * rule without one is that single line. Blocks never overlap — scanning resumes after the line that closed one.
 *
 * Pure apart from the disabled set, and meant for a background thread: nothing here touches the EDT.
 *
 * **Regex safety.** The rules are hand-written, so every regex invocation runs against a [CharSequence] whose
 * `charAt` checks a deadline and throws once [budgetMs] has passed (the standard `Pattern` interruption idiom;
 * a `StackOverflowError` from a regex that recurses too deep is treated the same way). A rule that blows the
 * budget is disabled for the rest of this detector's life and reported once through [onRuleDisabled], and
 * the scan carries on with the other rules — plain output is never corrupted, only left plain.
 */
class BlockDetector(
    private val budgetMs: Long = DEFAULT_BUDGET_MS,
    private val clock: () -> Long = System::nanoTime,
    private val onRuleDisabled: (EnhancerRule, String) -> Unit = { _, _ -> },
) {
    private val disabled = LinkedHashSet<String>()

    /** Ids of the rules this detector gave up on, in the order it did. */
    val disabledRuleIds: Set<String> get() = disabled

    /**
     * Scans [text] from the line containing [from] to the end. Rules run in the order given; disabled ones,
     * by the user or by a blown budget, are skipped.
     */
    fun detect(text: CharSequence, rules: List<EnhancerRule>, from: Int = 0): Detection {
        val active = rules.filter { it.enabled && it.id !in disabled }
        val blocks = ArrayList<EnhancedBlock>()
        var lineStart = lineStartOf(text, from.coerceIn(0, text.length))
        var resumeFrom = text.length
        val lines = LineWalker(text)
        while (lineStart < text.length) {
            val lineEnd = lines.endOf(lineStart)
            val opened = active.firstOrNull { rule -> rule.id !in disabled && matches(rule, rule.start, text, lineStart, lineEnd) }
            if (opened == null) {
                lineStart = lines.next(lineEnd)
                continue
            }
            val match = opened.start.matcher(guarded(text, lineStart, lineEnd)).also { it.find() }
            val summary = opened.summaryFor(match, text.subSequence(lineStart, contentEnd(text, lineStart, lineEnd)))
            if (opened.isSingleLine) {
                blocks += block(opened, text, lineStart, contentEnd(text, lineStart, lineEnd), summary)
                lineStart = lines.next(lineEnd)
                continue
            }
            val closing = findEnd(opened, text, lines, firstLineEnd = lineEnd)
            when (closing) {
                is EndSearch.Closed -> {
                    blocks += block(opened, text, lineStart, closing.lineEnd, summary)
                    lineStart = lines.next(closing.lineEnd)
                }
                // The text ran out before the block did: leave it for the next scan, which starts here.
                EndSearch.Pending -> {
                    resumeFrom = minOf(resumeFrom, lineStart)
                    lineStart = lines.next(lineEnd)
                }
                // Too long to be this block, or the rule just died: the opener is plain text after all.
                EndSearch.GivenUp -> lineStart = lines.next(lineEnd)
            }
        }
        return Detection(blocks, resumeFrom)
    }

    private sealed interface EndSearch {
        class Closed(val lineEnd: Int) : EndSearch
        object Pending : EndSearch
        object GivenUp : EndSearch
    }

    private fun findEnd(rule: EnhancerRule, text: CharSequence, lines: LineWalker, firstLineEnd: Int): EndSearch {
        val end = rule.end ?: return EndSearch.GivenUp
        var lineStart = lines.next(firstLineEnd)
        var seen = 1
        while (lineStart < text.length) {
            if (seen >= rule.maxLines) return EndSearch.GivenUp
            val lineEnd = lines.endOf(lineStart)
            if (rule.id in disabled) return EndSearch.GivenUp
            if (matches(rule, end, text, lineStart, lineEnd)) return EndSearch.Closed(contentEnd(text, lineStart, lineEnd))
            seen++
            lineStart = lines.next(lineEnd)
        }
        return EndSearch.Pending
    }

    private fun block(rule: EnhancerRule, text: CharSequence, start: Int, end: Int, summary: String) = EnhancedBlock(
        rule.id,
        TextRange(start, end),
        summary,
        rule.render,
        if (rule.render == RenderMode.FOLD) null else text.subSequence(start, end).toString(),
    )

    /** One guarded `find()`; a blown budget disables the rule and counts as no match. */
    private fun matches(rule: EnhancerRule, pattern: java.util.regex.Pattern, text: CharSequence, start: Int, end: Int): Boolean {
        return try {
            pattern.matcher(guarded(text, start, end)).find()
        } catch (e: BudgetExceeded) {
            disable(rule, "took longer than $budgetMs ms on one line")
            false
        } catch (e: StackOverflowError) {
            disable(rule, "recursed too deep on one line")
            false
        }
    }

    private fun disable(rule: EnhancerRule, reason: String) {
        if (disabled.add(rule.id)) onRuleDisabled(rule, reason)
    }

    private fun guarded(text: CharSequence, start: Int, end: Int): CharSequence =
        DeadlineCharSequence(text, start, contentEnd(text, start, end), clock() + budgetMs * 1_000_000, clock)

    private class BudgetExceeded : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** A window onto the output whose reads stop once the deadline has passed. */
    private class DeadlineCharSequence(
        private val base: CharSequence,
        private val start: Int,
        private val end: Int,
        private val deadline: Long,
        private val clock: () -> Long,
    ) : CharSequence {
        private var reads = 0

        override val length: Int get() = end - start

        override fun get(index: Int): Char {
            // Asking the clock on every read would cost more than the matching; every 256th is plenty.
            if ((++reads and 0xFF) == 0 && clock() > deadline) throw BudgetExceeded()
            return base[start + index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            base.subSequence(start + startIndex, start + endIndex).toString()

        override fun toString(): String = base.subSequence(start, end).toString()
    }

    /** Line arithmetic over the text, `\n`-terminated; the last line may lack its newline. */
    private class LineWalker(private val text: CharSequence) {
        fun endOf(lineStart: Int): Int {
            var i = lineStart
            while (i < text.length && text[i] != '\n') i++
            return i
        }

        fun next(lineEnd: Int): Int = if (lineEnd < text.length) lineEnd + 1 else text.length
    }

    companion object {
        const val DEFAULT_BUDGET_MS = 50L

        /** A trailing CR belongs to the line break, not the line. */
        private fun contentEnd(text: CharSequence, start: Int, end: Int): Int =
            if (end > start && text[end - 1] == '\r') end - 1 else end

        private fun lineStartOf(text: CharSequence, offset: Int): Int {
            var i = offset
            while (i > 0 && text[i - 1] != '\n') i--
            return i
        }

        /** The five rules that ship with the plugin, from `resources/terminal/rules/`, in the order they apply. */
        val BUILT_IN_RULE_FILES: List<String> = listOf(
            "php-var-dump.json", "php-print-r.json", "php-var-export.json", "php-stack-trace.json", "json-line.json",
        )

        fun builtInRules(): List<EnhancerRule> = BUILT_IN_RULE_FILES.map { name ->
            val text = BlockDetector::class.java.getResourceAsStream("/terminal/rules/$name")
                ?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("built-in rule $name is missing from the plugin")
            RuleParser.parse(text, EnhancerRule.BUILT_IN_SOURCE)
        }
    }
}
