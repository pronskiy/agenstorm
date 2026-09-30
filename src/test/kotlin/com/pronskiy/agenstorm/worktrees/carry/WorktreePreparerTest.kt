package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.openapi.util.io.NioFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

/** Steps T2.4 and T4.8: a worktree made elsewhere gets an `.idea` only when it has none, from the first source that has one. */
class WorktreePreparerTest {

    @Test
    fun theIdeaComesFromTheFirstSourceThatHasOneAndNeverOverAnExistingOne() {
        val root = Files.createTempDirectory("prep").toRealPath()
        try {
            val main = Files.createDirectories(root.resolve("main"))
            val current = Files.createDirectories(root.resolve("current/.idea")).parent
            Files.writeString(current.resolve(".idea/php.xml"), "<project/>")
            val fresh = Files.createDirectories(root.resolve("fresh"))
            val opened = Files.createDirectories(root.resolve("opened/.idea")).parent

            WorktreePreparer.seedIdea(fresh, listOf(main, current))
            WorktreePreparer.seedIdea(opened, listOf(current))

            assertEquals("<project/>", Files.readString(fresh.resolve(".idea/php.xml")))
            assertFalse(Files.exists(opened.resolve(".idea/php.xml")))
        } finally {
            NioFiles.deleteRecursively(root)
        }
    }
}
