package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.JDOMUtil
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Step T2.4. Gives a worktree the main checkout's project settings, so it opens with the same PHP interpreter,
 * Composer settings and run configurations: every file of the source `.idea` that the worktree's `.idea` lacks is
 * copied — the tracked ones arrive with the checkout and are never overwritten. `*.iml` and `modules.xml` go as they
 * are, their `$PROJECT_DIR$` paths being relative. The copied `workspace.xml` loses the per-checkout state: `ProjectId`
 * (a shared id makes the platform take two worktrees for one project), `ChangeListManager` and `TaskManager` (§7).
 * `shelf/` and `httpRequests/` hold local history and stay behind. Blocking.
 */
object IdeaSeeder {

    val DROPPED_COMPONENTS = setOf("ProjectId", "ChangeListManager", "TaskManager")
    private val SKIPPED = setOf("shelf", "httpRequests")
    private const val WORKSPACE = "workspace.xml"

    /** Returns the paths copied, relative to [source]. */
    fun seed(source: Path, target: Path): List<String> {
        if (!Files.isDirectory(source)) return emptyList()
        val copied = mutableListOf<String>()
        try {
            Files.walk(source).use { paths ->
                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                    val relative = source.relativize(file).toString().replace('\\', '/')
                    if (relative.substringBefore('/') in SKIPPED) return@forEach
                    val to = target.resolve(relative)
                    if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    Files.createDirectories(to.parent)
                    if (relative == WORKSPACE) Files.writeString(to, workspace(Files.readString(file)))
                    else Files.copy(file, to, StandardCopyOption.COPY_ATTRIBUTES)
                    copied += relative
                }
            }
        } catch (e: IOException) {
            LOG.warn("Could not seed $target from $source", e)
        }
        LOG.info("Seeded ${copied.size} files into $target")
        return copied.sorted()
    }

    /** `workspace.xml` without [DROPPED_COMPONENTS]; unchanged if it does not parse. */
    fun workspace(xml: String): String = try {
        val root = JDOMUtil.load(xml)
        root.getChildren("component").filter { it.getAttributeValue("name") in DROPPED_COMPONENTS }.forEach { it.detach() }
        JDOMUtil.write(root)
    } catch (e: Exception) {
        LOG.info("workspace.xml does not parse; copied as it is", e)
        xml
    }

    private val LOG = logger<IdeaSeeder>()
}
