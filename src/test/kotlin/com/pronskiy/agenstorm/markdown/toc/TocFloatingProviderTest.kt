package com.pronskiy.agenstorm.markdown.toc

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarProvider
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Step W2.5: the provider is registered, applies to Markdown editors only, attaches a controller and stays shown through Esc. */
class TocFloatingProviderTest : BasePlatformTestCase() {

    private class FakeToolbar : FloatingToolbarComponent {
        var shows = 0
        override var backgroundAlpha = 0f
        override var showingTime = 0
        override var hidingTime = 0
        override var retentionTime = 0
        override var autoHideable = false
        override fun scheduleHide() = Unit
        override fun scheduleShow() {
            shows++
        }
        override fun hideImmediately() = Unit
    }

    private fun context(): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.EDITOR, myFixture.editor)
        .add(CommonDataKeys.VIRTUAL_FILE, myFixture.file.virtualFile)
        .build()

    fun testTheProviderIsRegistered() {
        val providers = ExtensionPointName<FloatingToolbarProvider>("com.intellij.editorFloatingToolbarProvider").extensionList
        assertTrue(providers.any { it is TocFloatingProvider })
    }

    fun testAppliesToMarkdownOnly() {
        myFixture.configureByText("a.md", "# A\n")
        assertTrue(TocFloatingProvider.appliesTo(context()))
        myFixture.configureByText("a.php", "<?php\n")
        assertFalse(TocFloatingProvider.appliesTo(context()))
    }

    fun testRegisterAttachesAndShowsAndEscShowsAgain() {
        myFixture.configureByText("a.md", "# A\n\n## B\n")
        val toolbar = FakeToolbar()
        val provider = TocFloatingProvider()
        provider.register(context(), toolbar, testRootDisposable)
        assertNotNull(TocService.getInstance(project).controllerFor(myFixture.editor))
        assertEquals(1, toolbar.shows)
        provider.onHiddenByEsc(context())
        assertEquals(2, toolbar.shows)
    }

    fun testTheSlotNeverHidesByItself() {
        val provider = TocFloatingProvider()
        assertFalse(provider.autoHideable)
        assertEquals(0f, provider.backgroundAlpha)
    }
}
