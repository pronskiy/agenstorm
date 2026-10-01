package com.pronskiy.agenstorm.softwrap

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.impl.softwrap.mapping.SoftWrapApplianceManager

/**
 * Step V1.2 (decision 95). The editor's own soft-wrap width — [original], which knows the insets, the scrollbar and
 * Distraction-Free mode — capped at the right margin ([MarginWrap.width]). The margin is read on every call, so it is
 * the one the editor draws: per language, per project, as Code Style sets it. `SoftWrapApplianceManager` is an `impl`
 * class, not internal (SPEC.md §2); the platform applies the same cap itself in Distraction-Free mode.
 */
class MarginWidthProvider(
    private val editor: Editor,
    val original: SoftWrapApplianceManager.VisibleAreaWidthProvider,
) : SoftWrapApplianceManager.VisibleAreaWidthProvider {

    override fun getVisibleAreaWidth(): Int {
        val width = original.visibleAreaWidth
        if (editor.isDisposed) return width
        return MarginWrap.width(width, editor.settings.getRightMargin(editor.project), EditorUtil.getPlainSpaceWidth(editor))
    }
}
