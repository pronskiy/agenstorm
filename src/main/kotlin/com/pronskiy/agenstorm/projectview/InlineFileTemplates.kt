package com.pronskiy.agenstorm.projectview

import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt

/**
 * Step O1.3. The file template a typed name asks for, or `null` for a name no template matches.
 *
 * **This is not what the stock New | File does.** `CreateFileAction.create` is
 * `mkdirs.directory.createFile(name)` and nothing more, so typing `Foo.php` into the platform's dialog gives an
 * empty file; the `<?php` comes from PhpStorm's separate *PHP File* entry, which asks for a class name rather
 * than a file name. Agenstorm's field has only one entry, so the template is picked from the extension instead.
 */
object InlineFileTemplates {

    /**
     * Matched on extension, preferring a template whose name reads like `PHP File` over any other with the same
     * extension — the internal templates are the ones the New menu offers, so they are searched first.
     */
    fun templateFor(project: Project, fileName: String): FileTemplate? {
        val extension = FileUtilRt.getExtension(fileName)
        if (extension.isEmpty()) return null
        val manager = FileTemplateManager.getInstance(project)
        return manager.internalTemplates.bestFor(extension) ?: manager.allTemplates.bestFor(extension)
    }

    private fun Array<FileTemplate>.bestFor(extension: String): FileTemplate? {
        val matching = filter { it.extension.equals(extension, ignoreCase = true) }
        if (matching.isEmpty()) return null
        return matching.firstOrNull { it.name.endsWith(" File", ignoreCase = true) } ?: matching.first()
    }
}
