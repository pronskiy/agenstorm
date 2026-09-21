package com.pronskiy.agenstorm.markdown.tables

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.ColorUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.markdown.MarkdownBlockKind
import com.pronskiy.agenstorm.markdown.MarkdownBlockRenderer
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import javax.swing.JComponent

/**
 * The fonts a rendered table is drawn with (decision 68): the proportional UI font at the editor's size for text,
 * with bold and italic derived from it, and the editor's own font for code spans.
 */
class TableFonts(val text: Font, val bold: Font, val italic: Font, val boldItalic: Font, val code: Font) {

    fun of(run: StyledRun): Font = when {
        run.code -> code
        run.bold && run.italic -> boldItalic
        run.bold -> bold
        run.italic -> italic
        else -> text
    }

    companion object {
        fun of(scheme: EditorColorsScheme): TableFonts {
            val base: Font = UIUtil.getFontWithFallback(JBFont.label().deriveFont(scheme.editorFontSize2D))
            return TableFonts(
                text = base,
                bold = base.deriveFont(Font.BOLD),
                italic = base.deriveFont(Font.ITALIC),
                boldItalic = base.deriveFont(Font.BOLD or Font.ITALIC),
                code = scheme.getFont(EditorFontType.PLAIN),
            )
        }
    }
}

/** The colours, all taken from the editor's scheme so Light and Dark both work without a hard-coded value. */
class TablePalette(val foreground: Color, val rule: Color, val link: Color, val codeBackground: Color) {
    companion object {
        fun of(scheme: EditorColorsScheme): TablePalette = TablePalette(
            foreground = scheme.defaultForeground,
            rule = MarkdownBlockRenderer.ruleColor(scheme),
            link = scheme.getAttributes(CodeInsightColors.HYPERLINK_ATTRIBUTES)?.foregroundColor ?: JBUI.CurrentTheme.Link.Foreground.ENABLED,
            codeBackground = MarkdownBlockRenderer.background(scheme, MarkdownBlockKind.CODE_FENCE) ?: scheme.defaultBackground,
        )
    }
}

/** [TextMeasurer] over real font metrics; the line height and ascent are the tallest of the fonts in use. */
class FontMetricsMeasurer(private val component: JComponent, val fonts: TableFonts) : TextMeasurer {

    private val metrics = HashMap<Font, FontMetrics>()

    override val lineHeight: Int = listOf(fonts.text, fonts.bold, fonts.code).maxOf { metricsOf(it).height }

    val ascent: Int = listOf(fonts.text, fonts.bold, fonts.code).maxOf { metricsOf(it).ascent }

    override fun width(text: String, run: StyledRun): Int = metricsOf(fonts.of(run)).stringWidth(text)

    fun metricsOf(font: Font): FontMetrics = metrics.getOrPut(font) { component.getFontMetrics(font) }
}

/**
 * Step Q2.3. Draws a laid-out table: the header row in bold over a rule, a thinner rule between the body rows,
 * no vertical lines, links underlined in the hyperlink colour, code spans on the fence-card background, strikes
 * struck through. Everything is positioned by [TableGeometry]; this only puts ink where it says.
 */
object TablePainter {

    fun paint(g: Graphics2D, geometry: TableGeometry, x0: Int, y0: Int, measurer: FontMetricsMeasurer, palette: TablePalette) {
        UISettings.setupAntialiasing(g)
        val thin = JBUIScale.scale(1)
        val rows = geometry.rows
        for ((index, row) in rows.withIndex()) {
            val bottom = y0 + row.y + row.height
            g.color = if (index == 0) palette.rule else ColorUtil.withAlpha(palette.rule, BODY_RULE_ALPHA)
            val thickness = if (index == 0) JBUIScale.scale(HEADER_RULE) else thin
            if (index < rows.lastIndex || index == 0) g.fillRect(x0, bottom - thickness, geometry.width, thickness)
        }
        for (cell in geometry.cells) {
            for (positioned in cell.runs) {
                val run = positioned.run
                val font = measurer.fonts.of(run)
                val x = x0 + positioned.x
                val y = y0 + positioned.y
                val baseline = y + measurer.ascent
                if (run.code) {
                    g.color = palette.codeBackground
                    g.fillRoundRect(x - CODE_INSET, y, positioned.width + 2 * CODE_INSET, measurer.lineHeight, CODE_ARC, CODE_ARC)
                }
                g.font = font
                g.color = if (run.link != null) palette.link else palette.foreground
                g.drawString(positioned.text, x, baseline)
                if (run.link != null) g.fillRect(x, baseline + thin, positioned.width, thin)
                if (run.strike) g.fillRect(x, baseline - measurer.metricsOf(font).ascent / 3, positioned.width, thin)
            }
        }
    }

    private const val HEADER_RULE = 1
    private const val BODY_RULE_ALPHA = 0.55
    private val CODE_INSET = JBUIScale.scale(2)
    private val CODE_ARC = JBUIScale.scale(4)
}
