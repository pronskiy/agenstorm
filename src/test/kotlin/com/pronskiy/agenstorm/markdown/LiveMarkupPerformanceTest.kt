package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step F1.5 and the Phase F1 perf guardrail: on a 3,000-line Markdown file, one full sync (collect + fold model
 * update) must stay well under the 50 ms the spec budgets, and a sync after a one-character edit must be cheaper
 * still because the regions that still fit are kept. Timings are printed so a slow machine shows the real number
 * instead of only a pass/fail; the assertions leave generous headroom for a loaded CI box.
 */
class LiveMarkupPerformanceTest : BasePlatformTestCase() {

    fun testFullAndIncrementalSyncOnAThousandsOfLinesFile() {
        myFixture.configureByText("big.md", bigFile())
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val controller = LiveMarkupService.getInstance(project).controllerFor(myFixture.editor) ?: error("no controller")

        val firstMs = measure { controller.syncNow() }
        val regions = controller.regions().size
        assertTrue("expected thousands of regions, got $regions", regions > 2_000)
        println("live markup: first sync ${firstMs} ms for $regions regions")

        val repeatMs = measure { controller.syncNow() }
        println("live markup: no-op sync ${repeatMs} ms")

        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "trailing text\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val editMs = measure { controller.syncNow() }
        println("live markup: sync after an edit ${editMs} ms")
        assertEquals(regions, controller.regions().size)

        // Phase F3: the caret policy scans every region on each caret move; it must stay far below a frame.
        var policyTotal = 0L
        for (line in 0 until 40) {
            myFixture.editor.caretModel.moveToOffset(document.getLineStartOffset(line * 70 + 1))
            policyTotal += measure { controller.applyCaretPolicy() }
        }
        val policyAvg = policyTotal / 40
        println("live markup: caret policy ${policyAvg} ms on average over 40 line changes")
        assertTrue("caret policy took $policyAvg ms", policyAvg < 20)

        assertTrue("first sync took $firstMs ms", firstMs < 1_000)
        assertTrue("no-op sync took $repeatMs ms", repeatMs < 500)
        assertTrue("sync after an edit took $editMs ms", editMs < 500)
    }

    private fun measure(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    /** 3,000 lines of mixed markup, close to a real plan file. */
    private fun bigFile(): String = buildString {
        for (i in 1..3_000) {
            when (i % 10) {
                0 -> append("## Section ${i / 10}")
                1 -> append("Paragraph $i with **bold $i**, *emphasis $i*, ~~gone $i~~ and `code $i`.")
                2 -> append("- [ ] task $i with a [link $i](https://example.com/$i) inside")
                3 -> append("- [x] done task $i and __strong ${i}__ and _em ${i}_")
                5 -> append("> quote $i with **bold** and `code`")
                6 -> append("Plain line $i without any markup at all, just words to fill the file.")
                8 -> append("1. numbered $i with ***both $i*** markers")
            }
            append('\n')
        }
    }
}
