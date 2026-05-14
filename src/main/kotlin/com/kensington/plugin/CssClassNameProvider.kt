package com.kensington.plugin

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

class CssClassNameProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        if (!insideClassProperty(parameters)) return
        val project = parameters.position.project
        val prefix = currentWordPrefix(parameters)
        val resultSet = result.withPrefixMatcher(prefix)
        val cache = CdnCssCache.getInstance(project)
        val localSources = cache.getLocalClassSources()
        val localNames = cache.getLocalClassNames()
        val cdnNames = cache.getClassNames()

        // Local classes with their source filename as type text
        localNames.sorted().forEach { name ->
            resultSet.addElement(
                LookupElementBuilder.create(name)
                    .withTypeText(localSources[name] ?: "CSS", true)
            )
        }
        // CDN classes not already covered by local files
        (cdnNames - localNames).sorted().forEach { name ->
            resultSet.addElement(
                LookupElementBuilder.create(name)
                    .withTypeText("CDN", true)
            )
        }
    }

    private fun currentWordPrefix(parameters: CompletionParameters): String {
        val text = parameters.position.text
        val offsetInElement = parameters.offset - parameters.position.textRange.startOffset
        return text.substring(0, offsetInElement)
            .trimStart('\'', '"', '`')
            .substringAfterLast(' ')
    }

    private fun insideClassProperty(parameters: CompletionParameters): Boolean {
        val property = PsiTreeUtil.getParentOfType(
            parameters.position, JSProperty::class.java
        ) ?: return false
        if (property.name != "class") return false
        val literal = PsiTreeUtil.getParentOfType(
            parameters.position, JSLiteralExpression::class.java
        ) ?: return false
        return when (val parent = literal.parent) {
            property -> true                                          // { class: 'foo' }
            is JSArrayLiteralExpression -> parent.parent == property // { class: ['foo'] }
            else -> false
        }
    }
}
