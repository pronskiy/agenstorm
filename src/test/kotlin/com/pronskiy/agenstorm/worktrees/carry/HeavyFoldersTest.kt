package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.openapi.util.io.NioFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Step T2.3: the heavy folders, parsed from the setting and cloned when the worktree lacks them. */
class HeavyFoldersTest {

    @Test
    fun theSettingIsAListOfSafeRelativeFolders() {
        assertEquals(listOf("vendor", "node_modules"), HeavyFolders.parse(HeavyFolders.DEFAULT))
        assertEquals(listOf("vendor", "web/node_modules", ".worktrees"), HeavyFolders.parse(" vendor/ ,\nweb/node_modules, vendor, ../escape, , .worktrees"))
        assertEquals(emptyList<String>(), HeavyFolders.parse(""))
    }

    @Test
    fun onlyFoldersTheMainCheckoutHasAndTheWorktreeLacksAreCloned() {
        val root = Files.createTempDirectory("heavy").toRealPath()
        try {
            val main = Files.createDirectories(root.resolve("main"))
            val worktree = Files.createDirectories(root.resolve("wt"))
            Files.createDirectories(main.resolve("vendor/acme"))
            Files.writeString(main.resolve("vendor/acme/Lib.php"), "<?php")
            Files.createDirectories(main.resolve("node_modules"))
            Files.createDirectories(worktree.resolve("node_modules"))

            Files.createDirectories(main.resolve(".worktrees/other"))

            assertEquals(listOf("vendor"), HeavyFolders.clone(main, worktree, listOf("vendor", "node_modules", "missing", ".worktrees", "."), skip = listOf(".worktrees")))
            assertEquals("<?php", Files.readString(worktree.resolve("vendor/acme/Lib.php")))
            assertFalse(Files.exists(worktree.resolve("missing")))
        } finally {
            NioFiles.deleteRecursively(root)
        }
    }

    @Test
    fun aFolderTheWorktreeHasInPartIsFilledInNotSkipped() {
        val root = Files.createTempDirectory("heavy").toRealPath()
        try {
            val main = Files.createDirectories(root.resolve("main"))
            val worktree = Files.createDirectories(root.resolve("wt"))
            for (file in listOf("vendor/acme/Lib.php", "vendor/acme/skeleton/.env.example", "vendor/other/Other.php")) {
                Files.createDirectories(main.resolve(file).parent)
                Files.writeString(main.resolve(file), "main")
            }
            Files.createDirectories(worktree.resolve("vendor/acme/skeleton"))
            Files.writeString(worktree.resolve("vendor/acme/skeleton/.env.example"), "already here")

            assertEquals(listOf("vendor"), HeavyFolders.clone(main, worktree, listOf("vendor")))
            assertEquals("main", Files.readString(worktree.resolve("vendor/acme/Lib.php")))
            assertEquals("main", Files.readString(worktree.resolve("vendor/other/Other.php")))
            assertEquals("already here", Files.readString(worktree.resolve("vendor/acme/skeleton/.env.example")))
        } finally {
            NioFiles.deleteRecursively(root)
        }
    }

    @Test
    fun aWorktreeMadeElsewhereKeepsAFolderItHasAtAll() {
        val root = Files.createTempDirectory("heavy").toRealPath()
        try {
            val main = Files.createDirectories(root.resolve("main"))
            val worktree = Files.createDirectories(root.resolve("wt"))
            Files.createDirectories(main.resolve("vendor/acme"))
            Files.writeString(main.resolve("vendor/acme/Lib.php"), "<?php // main's")
            Files.createDirectories(main.resolve("node_modules/left"))
            Files.createDirectories(worktree.resolve("vendor/other"))

            assertEquals(listOf("node_modules"), HeavyFolders.clone(main, worktree, listOf("vendor", "node_modules"), onlyAbsent = true))
            assertFalse(Files.exists(worktree.resolve("vendor/acme")))
            assertTrue(Files.isDirectory(worktree.resolve("node_modules/left")))
        } finally {
            NioFiles.deleteRecursively(root)
        }
    }
}
