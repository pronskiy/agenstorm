package com.pronskiy.agenstorm.links.php

import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.pronskiy.agenstorm.links.comments.CommentLocationReferenceProvider

/**
 * `path:line[:col]` tokens inside PHP string literals (quoted strings, heredoc, nowdoc). Reuses the comment
 * provider: same parser, same soft references, same 20 KB cap. Registered with low priority so PHP's own
 * string references (class names, file paths) keep precedence.
 */
class PhpStringLocationReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(StringLiteralExpression::class.java),
            CommentLocationReferenceProvider(),
            PsiReferenceRegistrar.LOWER_PRIORITY,
        )
    }
}
