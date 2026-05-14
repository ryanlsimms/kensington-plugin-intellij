package com.kensington.plugin

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

class KensingtonGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor
    ): Array<PsiElement>? {
        sourceElement ?: return null

        val literal = PsiTreeUtil.getParentOfType(sourceElement, JSLiteralExpression::class.java, false)
            ?: return null

        if (!isClassPropertyLiteral(literal)) return null

        val className = classNameAtOffset(literal, offset) ?: return null

        val project = literal.project
        val cache = CdnCssCache.getInstance(project)
        val vf = cache.getLocalClassFile(className)?.takeIf { it.isValid } ?: return null
        val cssOffset = CssScanner.findClassOffset(vf, className) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
        val target = psiFile.findElementAt(cssOffset) ?: psiFile
        return arrayOf(target)
    }

    private fun isClassPropertyLiteral(literal: JSLiteralExpression): Boolean {
        val property = PsiTreeUtil.getParentOfType(literal, JSProperty::class.java) ?: return false
        if (property.name != "class") return false
        return when (val parent = literal.parent) {
            property -> true
            is JSArrayLiteralExpression -> parent.parent == property
            else -> false
        }
    }

    private fun classNameAtOffset(literal: JSLiteralExpression, docOffset: Int): String? {
        val raw = literal.text ?: return null
        val quote = when {
            raw.startsWith('\'') || raw.startsWith('"') -> raw[0]
            raw.startsWith('`') && !raw.contains("\${") -> '`'
            else -> return null
        }
        val content = raw.removePrefix(quote.toString()).removeSuffix(quote.toString())
        val offsetInContent = docOffset - literal.textRange.startOffset - 1  // -1 for opening quote
        if (offsetInContent < 0 || offsetInContent > content.length) return null
        return tokenAtOffset(content, offsetInContent)
    }

    private fun tokenAtOffset(content: String, offsetInContent: Int): String? {
        var i = 0
        while (i < content.length) {
            while (i < content.length && content[i].isWhitespace()) i++
            val start = i
            while (i < content.length && !content[i].isWhitespace()) i++
            if (start < i && offsetInContent in start..i) return content.substring(start, i)
        }
        return null
    }
}
