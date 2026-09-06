package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.components.service
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step G2.2: what a directory argument means. A directory the project already contains is shown in the
 * Project view rather than opened as a second project; anything else becomes a project to open.
 *
 * The choice is asserted, not performed — opening or focusing a window is what the Epic G guardrail covers.
 */
class OpenProjectClassificationTest : BasePlatformTestCase() {

    private lateinit var server: OpenRequestServer
    private lateinit var outside: Path
    private lateinit var contentRoot: Path

    override fun setUp() {
        super.setUp()
        server = project.service<OpenRequestServer>()
        outside = Files.createTempDirectory("agenstorm-outside").toRealPath()
        contentRoot = Files.createTempDirectory("agenstorm-content").toRealPath()
        Files.createDirectories(contentRoot.resolve("src/deep"))
        VfsRootAccess.allowRootAccess(testRootDisposable, outside.toString(), contentRoot.toString())
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(contentRoot)!!
        PsiTestUtil.addContentRoot(module, root)
    }

    override fun tearDown() {
        try {
            NioFiles.deleteRecursively(outside)
            NioFiles.deleteRecursively(contentRoot)
        } finally {
            super.tearDown()
        }
    }

    fun testADirectoryInsideTheProjectIsShownInTheProjectView() {
        val action = server.classifyProject(contentRoot.resolve("src/deep"))

        val inside = assertInstanceOf(action, OpenRequestServer.ProjectAction.SelectInside::class.java)
        assertEquals("deep", inside.directory.name)
    }

    fun testAContentRootItselfIsShownInTheProjectView() {
        assertInstanceOf(
            server.classifyProject(contentRoot),
            OpenRequestServer.ProjectAction.SelectInside::class.java,
        )
    }

    fun testADirectoryOutsideEveryOpenProjectBecomesAProjectToOpen() {
        val action = server.classifyProject(outside)

        assertEquals(outside, assertInstanceOf(action, OpenRequestServer.ProjectAction.OpenNew::class.java).path)
    }

    fun testAPathThatIsNotOnDiskAtAllStillBecomesAProjectToOpen() {
        // The router only ever hands over directories it saw on disk, so this is belt and braces.
        val action = server.classifyProject(outside.resolve("gone"))

        assertInstanceOf(action, OpenRequestServer.ProjectAction.OpenNew::class.java)
    }
}
