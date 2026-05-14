package com.kensington.plugin

import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider

class KensingtonUsageTypeProvider : UsageTypeProvider {
    override fun getUsageType(element: PsiElement): UsageType? {
        val literal = PsiTreeUtil.getParentOfType(element, JSLiteralExpression::class.java, false)
            ?: return null
        val property = PsiTreeUtil.getParentOfType(literal, JSProperty::class.java) ?: return null
        if (property.name != "class") return null
        when (val parent = literal.parent) {
            property -> Unit
            is JSArrayLiteralExpression -> if (parent.parent != property) return null
            else -> return null
        }
        return USAGE_TYPE
    }

    companion object {
        private val USAGE_TYPE = UsageType { "HTML class attribute" }
    }
}
