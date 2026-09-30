package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.openapi.util.io.NioFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Step T2.4: a worktree's `.idea` from the main checkout's, without what belongs to one checkout only. */
class IdeaSeederTest {

    private lateinit var root: Path
    private lateinit var source: Path
    private lateinit var target: Path

    private fun write(dir: Path, relative: String, text: String) {
        val file = dir.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("seed").toRealPath()
        source = root.resolve("main/.idea")
        target = root.resolve("wt/.idea")
        write(source, "php.xml", "<project><component name=\"PhpProjectSharedConfiguration\" php_language_level=\"8.4\"/></project>")
        write(source, "app.iml", "<module><component name=\"NewModuleRootManager\"><content url=\"file://\$MODULE_DIR\$\"/></component></module>")
        write(source, "modules.xml", "<project/>")
        write(source, "vcs.xml", "<project>main's</project>")
        write(source, "runConfigurations/Tests.xml", "<component name=\"ProjectRunConfigurationManager\"/>")
        write(source, "shelf/Changes.xml", "<changelist/>")
        write(source, "httpRequests/http-requests-log.http", "GET /")
        write(source, "workspace.xml", """
            <project version="4">
              <component name="ChangeListManager"><list default="true" id="x" name="Changes"/></component>
              <component name="ProjectId" id="2mRkRkSomeId"/>
              <component name="RunManager" selected="PHPUnit.Tests"><configuration name="Tests" type="PHPUnitRunConfigurationType"/></component>
              <component name="TaskManager"><task active="true" id="Default"/></component>
            </project>
        """.trimIndent())
        write(target, "vcs.xml", "<project>the worktree's own, tracked</project>")
    }

    @After
    fun tearDown() {
        NioFiles.deleteRecursively(root)
    }

    @Test
    fun whatTheWorktreeLacksIsCopiedAndNothingIsOverwritten() {
        val copied = IdeaSeeder.seed(source, target)

        assertEquals(listOf("app.iml", "modules.xml", "php.xml", "runConfigurations/Tests.xml", "workspace.xml"), copied)
        assertEquals("<project>the worktree's own, tracked</project>", Files.readString(target.resolve("vcs.xml")))
        assertTrue(Files.readString(target.resolve("php.xml")).contains("8.4"))
        assertFalse(Files.exists(target.resolve("shelf")))
        assertFalse(Files.exists(target.resolve("httpRequests")))
    }

    @Test
    fun theWorkspaceLosesItsProjectIdChangeListsAndTasksButKeepsRunConfigurations() {
        IdeaSeeder.seed(source, target)
        val workspace = Files.readString(target.resolve("workspace.xml"))

        assertFalse(workspace.contains("ProjectId"))
        assertFalse(workspace.contains("ChangeListManager"))
        assertFalse(workspace.contains("TaskManager"))
        assertTrue(workspace.contains("PHPUnit.Tests"))
    }

    @Test
    fun aSourceWithoutModulesXmlGetsTheDefaultModuleAndOneWithItKeepsItsOwn() {
        IdeaSeeder.seed(source, target)
        assertEquals("<project/>", Files.readString(target.resolve("modules.xml")))

        val bare = root.resolve("bare/.idea")
        write(bare, "php.xml", "<project/>")
        val worktree = root.resolve("disk-check/.idea")

        val copied = IdeaSeeder.seed(bare, worktree)

        assertEquals(listOf("disk-check.iml", "modules.xml", "php.xml"), copied)
        assertTrue(Files.readString(worktree.resolve("modules.xml")).contains("\$PROJECT_DIR\$/.idea/disk-check.iml"))
        assertTrue(Files.readString(worktree.resolve("disk-check.iml")).contains("<content url=\"file://\$MODULE_DIR\$\" />"))
    }

    @Test
    fun aWorkspaceThatDoesNotParseIsCopiedAsItIsAndNoSourceMeansNothing() {
        assertEquals("<project", IdeaSeeder.workspace("<project"))
        assertEquals(emptyList<String>(), IdeaSeeder.seed(root.resolve("nothing/.idea"), target))
    }
}
