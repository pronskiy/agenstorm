package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * R4 regression: `editorCreated` runs on the EDT without a read action, and `markActive` reached
 * `PsiManager.findFile` from there — 10 SEVERE "Read access is allowed from inside read-action only"
 * entries, all blamed on Agenstorm, in one PhpStorm 2026.2 session.
 */
class LiveMarkupThreadingTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData/markdown"

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testAttachDoesNotTouchPsiWithoutReadAccess() {
        myFixture.configureByFile("collector.md")
        val editor = myFixture.editor
        val service = LiveMarkupService.getInstance(project)
        service.detach(editor)

        val failure = ApplicationManager.getApplication().executeOnPooledThread<Throwable?> {
            try {
                service.attach(editor)
                null
            } catch (t: Throwable) {
                t
            }
        }.get()

        if (failure != null) throw AssertionError("attach() off the EDT without read access failed", failure)
    }
}
