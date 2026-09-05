package com.pronskiy.agenstorm.frame

import com.intellij.openapi.wm.impl.FrameTitleBuilder
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import kotlinx.coroutines.runBlocking

/**
 * Steps C1.1–C1.3: the application service FrameTitleBuilder is ours, and the file part of the window
 * title is empty while the feature is on and the platform's default while it is off.
 */
class ProjectOnlyFrameTitleBuilderTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testOurBuilderOverridesThePlatformService() {
        assertTrue(FrameTitleBuilder.getInstance() is ProjectOnlyFrameTitleBuilder)
    }

    fun testFileTitleIsEmptyWhileEnabled() {
        val file = myFixture.addFileToProject("src/Foo.php", "<?php\n").virtualFile
        val builder = FrameTitleBuilder.getInstance()

        assertEquals("", builder.getFileTitle(project, file))
        assertEquals("", runBlocking { builder.getFileTitleAsync(project, file) })
    }

    fun testFileTitleFallsBackToThePlatformWhileDisabled() {
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State(hideFileNameInTitle = false))
        val file = myFixture.addFileToProject("src/Foo.php", "<?php\n").virtualFile
        val builder = FrameTitleBuilder.getInstance()

        val title = builder.getFileTitle(project, file)
        assertTrue("expected the platform title to mention the file, got '$title'", title.contains("Foo.php"))
        assertEquals(title, runBlocking { builder.getFileTitleAsync(project, file) })
    }

    fun testProjectTitleIsUntouched() {
        val builder = FrameTitleBuilder.getInstance()
        assertEquals(project.name, builder.getProjectTitle(project))
    }
}
