package com.kensington.plugin

import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

class KensingtonCssReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JSLiteralExpression::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    val literal = element as? JSLiteralExpression ?: return PsiReference.EMPTY_ARRAY
                    if (!isInsideClassProperty(literal)) return PsiReference.EMPTY_ARRAY
                    return referencesForLiteral(literal)
                }
            }
        )
    }

    private fun isInsideClassProperty(literal: JSLiteralExpression): Boolean {
        val property = PsiTreeUtil.getParentOfType(literal, JSProperty::class.java) ?: return false
        if (property.name != "class") return false
        val arrayParent = PsiTreeUtil.getParentOfType(literal, JSArrayLiteralExpression::class.java)
        if (arrayParent != null) return arrayParent.parent == property
        return true
    }

    private fun referencesForLiteral(literal: JSLiteralExpression): Array<PsiReference> {
        val raw = literal.text ?: return PsiReference.EMPTY_ARRAY
        val quote = when {
            raw.startsWith('\'') || raw.startsWith('"') -> raw[0]
            raw.startsWith('`') && !raw.contains("\${") -> '`'
            else -> return PsiReference.EMPTY_ARRAY
        }
        val content = raw.removePrefix(quote.toString()).removeSuffix(quote.toString())
        val refs = mutableListOf<PsiReference>()
        var i = 0
        while (i < content.length) {
            while (i < content.length && content[i].isWhitespace()) i++
            if (i >= content.length) break
            val start = i
            while (i < content.length && !content[i].isWhitespace()) i++
            val token = content.substring(start, i)
            if (token.isNotEmpty()) {
                refs.add(KensingtonCssReference(literal, TextRange(1 + start, 1 + i), token))
            }
        }
        return refs.toTypedArray()
    }
}

/**
 * Reference from a class token in a Kensington template to its CSS selector definition.
 * Soft so unresolved CDN-only class names don't produce error highlights.
 */
internal class KensingtonCssReference(
    element: JSLiteralExpression,
    range: TextRange,
    private val className: String
) : PsiReferenceBase<JSLiteralExpression>(element, range, true) {

    override fun resolve(): PsiElement? {
        val project = element.project
        val cache = CdnCssCache.getInstance(project)
        val vf = cache.getLocalClassFile(className)?.takeIf { it.isValid } ?: return null
        val offset = CssScanner.findClassOffset(vf, className) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
        return psiFile.findElementAt(offset) ?: psiFile
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
