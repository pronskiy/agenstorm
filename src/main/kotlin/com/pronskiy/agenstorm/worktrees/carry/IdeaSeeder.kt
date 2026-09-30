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
 * `shelf/` and `httpRequests/` hold local history and stay behind.
 *
 * A composer project opened in 2026.3 may keep its module out of `.idea` altogether (no `modules.xml`, no `.iml`) in
 * the per-location workspace-model cache; a copied `.idea` without `modules.xml` then opens as an existing project with
 * no module, and its own sources are never indexed (T2 guardrail, laravel/framework). So when the source has no
 * `modules.xml`, the target gets the default module a directory project gets — a `WEB_MODULE` over the whole folder —
 * and Composer sync adds the source roots as usual. Blocking.
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
        if (!Files.exists(source.resolve(MODULES)) && !Files.exists(target.resolve(MODULES))) copied += defaultModule(target)
        LOG.info("Seeded ${copied.size} files into $target")
        return copied.sorted()
    }

    /** Writes `modules.xml` and `<folder>.iml` for a single module over the project folder; returns what it wrote. */
    private fun defaultModule(idea: Path): List<String> = try {
        val name = idea.toAbsolutePath().parent.fileName.toString()
        Files.createDirectories(idea)
        Files.writeString(idea.resolve(MODULES), MODULES_XML.replace("NAME", name))
        Files.writeString(idea.resolve("$name.iml"), MODULE_IML)
        listOf(MODULES, "$name.iml")
    } catch (e: IOException) {
        LOG.warn("Could not write a default module into $idea", e)
        emptyList()
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

    private const val MODULES = "modules.xml"
    private val MODULES_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project version="4">
          <component name="ProjectModuleManager">
            <modules>
              <module fileurl="file://${'$'}PROJECT_DIR${'$'}/.idea/NAME.iml" filepath="${'$'}PROJECT_DIR${'$'}/.idea/NAME.iml" />
            </modules>
          </component>
        </project>
    """.trimIndent() + "\n"
    private val MODULE_IML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <module type="WEB_MODULE" version="4">
          <component name="NewModuleRootManager">
            <content url="file://${'$'}MODULE_DIR${'$'}" />
            <orderEntry type="inheritedJdk" />
            <orderEntry type="sourceFolder" forTests="false" />
          </component>
        </module>
    """.trimIndent() + "\n"

    private val LOG = logger<IdeaSeeder>()
}
