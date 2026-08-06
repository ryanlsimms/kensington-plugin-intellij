package com.kensington.plugin

import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

class KensingtonClassUsagesSearcher :
    QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>() {

    private val jsExtensions = setOf("js", "ts", "mjs", "cjs")

    override fun processQuery(
        params: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ) {
        val context = ReadAction.computeCancellable<SearchContext?, RuntimeException> {
            val className = cssClassNameOf(params.elementToSearch) ?: return@computeCancellable null
            SearchContext(className, params.elementToSearch.project, params.effectiveSearchScope)
        } ?: return

        val psiManager = PsiManager.getInstance(context.project)
        for (file in ProjectFileScanner.collectFiles(context.project, jsExtensions)) {
            ProgressManager.checkCanceled()
            val shouldContinue = ReadAction.computeCancellable<Boolean, RuntimeException> {
                if (!file.isValid || !context.scope.contains(file)) {
                    true
                } else {
                    psiManager.findFile(file)?.let {
                        scanFile(it, context.className, consumer)
                    } ?: true
                }
            }
            if (!shouldContinue) return
        }
    }

    private fun scanFile(
        psiFile: com.intellij.psi.PsiFile,
        className: String,
        consumer: Processor<in PsiReference>
    ): Boolean {
        var shouldContinue = true
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (!shouldContinue) return

                val literal = element as? JSLiteralExpression
                if (literal != null && isClassPropertyLiteral(literal)) {
                    val raw = literal.text
                    val quoteChar = when {
                        raw.startsWith('\'') || raw.startsWith('"') -> raw[0]
                        raw.startsWith('`') && !raw.contains("\${") -> '`'
                        else -> null
                    }
                    if (quoteChar != null) {
                        val content = raw
                            .removePrefix(quoteChar.toString())
                            .removeSuffix(quoteChar.toString())

                        var i = 0
                        while (i < content.length && shouldContinue) {
                            while (i < content.length && content[i].isWhitespace()) i++
                            val start = i
                            while (i < content.length && !content[i].isWhitespace()) i++
                            if (i > start && content.substring(start, i) == className) {
                                shouldContinue = consumer.process(
                                    KensingtonCssReference(
                                        literal,
                                        TextRange(1 + start, 1 + i),
                                        className
                                    )
                                )
                            }
                        }
                    }
                }

                if (shouldContinue) super.visitElement(element)
            }
        })
        return shouldContinue
    }

    /**
     * Returns the CSS class name if `element` is a class selector identifier in a CSS/SCSS/Less file.
     * Handles both the `btn` form (identifier leaf, dot is a sibling) and the `.btn` form
     * (some CSS PSI representations include the dot in the element text).
     */
    private fun cssClassNameOf(element: PsiElement): String? {
        val file = element.containingFile ?: return null
        val ext = file.virtualFile?.extension ?: return null
        if (ext !in setOf("css", "scss", "less")) return null

        val rawText = element.text ?: return null
        // Strip leading dot if the PSI element text includes it (e.g. ".btn")
        val name = rawText.removePrefix(".")
        if (!name.matches(Regex("[a-zA-Z_][\\w-]*"))) return null

        // Confirm it's a class selector context
        if (rawText.startsWith('.')) return name  // dot is part of the element text
        // Otherwise the dot must be the character immediately before this element in the file
        val startOffset = element.textRange.startOffset
        if (startOffset == 0) return null
        if ((file.text ?: return null)[startOffset - 1] != '.') return null

        return name
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

    private data class SearchContext(
        val className: String,
        val project: Project,
        val scope: SearchScope
    )
}
