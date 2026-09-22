package com.pronskiy.agenstorm.terminal.enhance

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * Step I2.1: the controller over a plain editor standing in for the terminal's output editor — regions appear
 * for blocks, only the appended tail is rescanned, a trim moves the scan position and drops a cut region, a
 * region someone else removed comes back, and disposing takes every region with it.
 */
class TerminalEnhancerControllerTest : BasePlatformTestCase() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rules = BlockDetector.builtInRules()
    private var controller: TerminalEnhancerController? = null

    override fun tearDown() {
        try {
            controller?.let(Disposer::dispose)
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    private fun fixture(name: String) = File("src/test/testData/terminal/enhance/$name").readText()

    private fun open(text: String): TerminalEnhancerController {
        myFixture.configureByText("out.txt", text)
        return TerminalEnhancerController(myFixture.editor as EditorEx, { rules }, scope, backgroundSync = false)
            .also { controller = it; it.syncNow() }
    }

    private fun append(text: String) {
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(myFixture.editor.document.textLength, text) }
    }

    private fun placeholders() = controller!!.regions().map { it.placeholderText }

    fun testABlockBecomesACollapsedRegionWithItsSummary() {
        val text = fixture("var-dump.txt")
        open(text)

        val region = controller!!.regions().single()

        assertEquals("array(2) …", region.placeholderText)
        assertFalse(region.isExpanded)
        assertEquals(text.indexOf("array(2) {"), region.startOffset)
        assertEquals("php-var-dump", region.getUserData(TerminalEnhancerController.RULE))
        assertEquals(text.length, controller!!.scanPosition)
    }

    fun testAppendedOutputIsScannedFromWhereTheLastScanStopped() {
        open("plain\n")
        assertTrue(placeholders().isEmpty())

        append(fixture("stack-trace.txt"))
        controller!!.syncNow()

        assertEquals(listOf("Stack trace …"), placeholders())
        val first = controller!!.regions().single()

        append(fixture("json-line.txt"))
        controller!!.syncNow()

        assertEquals(listOf("Stack trace …", "JSON {…}", "JSON […]"), placeholders())
        assertSame("the earlier region is kept, not recreated", first, controller!!.regions().first())
    }

    fun testABlockStillOpenWaitsForItsEnd() {
        val whole = fixture("var-dump.txt")
        val cut = whole.indexOf("  [\"b\"]=>")
        open(whole.substring(0, cut))
        assertTrue(placeholders().isEmpty())
        assertEquals(whole.indexOf("array(2) {"), controller!!.scanPosition)

        append(whole.substring(cut))
        controller!!.syncNow()

        assertEquals(listOf("array(2) …"), placeholders())
    }

    fun testATrimMovesTheScanPositionAndDropsTheRegionItCutThrough() {
        val trace = fixture("stack-trace.txt")
        val text = "before\n" + trace + fixture("json-line.txt")
        open(text)
        assertEquals(listOf("Stack trace …", "JSON {…}", "JSON […]"), placeholders())
        val cutAt = text.indexOf("#1 /app")

        // The terminal trims by characters from the top, through the middle of the stack trace.
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.deleteString(0, cutAt) }
        controller!!.syncNow()

        assertEquals(text.length - cutAt, controller!!.scanPosition)
        assertEquals(listOf("JSON {…}", "JSON […]"), placeholders())
        assertTrue(controller!!.regions().all { it.startOffset > 0 })
    }

    fun testARegionSomeoneElseRemovedComesBackOnTheNextSync() {
        open(fixture("stack-trace.txt"))
        val region = controller!!.regions().single()

        val model = myFixture.editor.foldingModel
        model.runBatchFoldingOperation { model.removeFoldRegion(region) }
        assertTrue(placeholders().isEmpty())
        controller!!.syncNow()

        assertEquals(listOf("Stack trace …"), placeholders())
    }

    fun testARegionTheUserExpandedStaysExpanded() {
        open(fixture("var-dump.txt"))
        val region = controller!!.regions().single()
        myFixture.editor.foldingModel.runBatchFoldingOperation { region.isExpanded = true }

        append("more\n")
        controller!!.syncNow()

        assertTrue(controller!!.regions().single().isExpanded)
    }

    fun testAClickOnATreePlaceholderOpensTheViewerAndKeepsTheRegionCollapsed() {
        val requests = ArrayList<TerminalEnhancerController.ViewerRequest>()
        val text = fixture("var-dump.txt")
        myFixture.configureByText("out.txt", text)
        controller = TerminalEnhancerController(myFixture.editor as EditorEx, { rules }, scope, backgroundSync = false, viewer = { requests += it })
        controller!!.syncNow()
        val region = controller!!.regions().single()

        assertTrue(controller!!.clickAt(region.startOffset + 3))

        val request = requests.single()
        assertEquals("php-var-dump", request.ruleId)
        assertEquals(RenderMode.TREE, request.render)
        assertTrue(request.raw.startsWith("array(2) {") && request.raw.endsWith("\n}"))
        assertEquals("array(2)", request.root.label)
        assertFalse(region.isExpanded)
    }

    fun testAClickOnAFoldPlaceholderOrPlainTextIsNotOurs() {
        val requests = ArrayList<TerminalEnhancerController.ViewerRequest>()
        myFixture.configureByText("out.txt", fixture("stack-trace.txt"))
        controller = TerminalEnhancerController(myFixture.editor as EditorEx, { rules }, scope, backgroundSync = false, viewer = { requests += it })
        controller!!.syncNow()
        val region = controller!!.regions().single()

        assertFalse(controller!!.clickAt(region.startOffset + 3))
        assertFalse(controller!!.clickAt(0))
        assertTrue(requests.isEmpty())
    }

    fun testDisposingRemovesEveryRegion() {
        open(fixture("var-dump.txt"))
        assertEquals(1, controller!!.regions().size)

        Disposer.dispose(controller!!)
        controller = null

        assertTrue(myFixture.editor.foldingModel.allFoldRegions.isEmpty())
    }
}
