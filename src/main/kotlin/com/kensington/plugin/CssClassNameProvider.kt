package com.kensington.plugin

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
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
        (cache.getLocalClassNames() + cache.getClassNames()).sorted().forEach { name ->
            resultSet.addElement(LookupElementBuilder.create(name))
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
        return property.name == "class"
    }
}
