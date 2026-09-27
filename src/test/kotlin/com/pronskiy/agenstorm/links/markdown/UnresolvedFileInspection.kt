package com.pronskiy.agenstorm.links.markdown

import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.codeInspection.LocalInspectionEP

/**
 * The Markdown plugin's "Cannot resolve file" inspection, looked up by its short name: the class moved from
 * `org.intellij.plugins.markdown.lang.references.paths` to `com.intellij.markdown.backend.inspections` in 2026.3.
 */
internal fun unresolvedFileInspection(): InspectionProfileEntry =
    LocalInspectionEP.LOCAL_INSPECTION.extensionList
        .single { it.shortName == "MarkdownUnresolvedFileReference" }
        .instantiateTool()
