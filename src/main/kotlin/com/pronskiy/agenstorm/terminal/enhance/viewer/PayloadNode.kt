package com.pronskiy.agenstorm.terminal.enhance.viewer

/**
 * Step I2.2. One node of a parsed block: a [key] where the value had one (`["a"]`, `[0]`, `'b'`, `"id"`), the
 * [text] of the value as the output wrote it (`int(1)`, `array(2)`, `"hello"`, `{3}`), and the children of a
 * container. The viewer shows [label]; the context menu copies [subtreeText].
 */
class PayloadNode(val key: String?, val text: String, val children: List<PayloadNode> = emptyList()) {

    val isContainer: Boolean get() = children.isNotEmpty()

    val label: String get() = if (key == null) text else "$key => $text"

    /** This node and everything under it, one line each, indented two spaces per level. */
    fun subtreeText(): String = buildString { write(this, 0) }

    private fun write(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append("  ") }
        out.append(label).append('\n')
        for (child in children) child.write(out, depth + 1)
    }

    override fun toString(): String = label
}
