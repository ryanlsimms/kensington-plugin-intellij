package com.kensington.plugin

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ui.Messages
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.lang.javascript.psi.JSElementVisitor
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.Border
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.xmlb.annotations.XCollection

class KensingtonClassInspection : LocalInspectionTool() {

    @JvmField
    @XCollection(elementName = "class")
    var ignoredClasses: ArrayList<String> = ArrayList()

    override fun createOptionsPanel(): JComponent {
        val model = DefaultListModel<String>().also { m ->
            synchronized(ignoredClasses) { ignoredClasses.forEach(m::addElement) }
        }
        val list = JBList(model)
        list.emptyText.text = "No ignored classes"
        val decorator = ToolbarDecorator.createDecorator(list)
            .setAddAction {
                val name = Messages.showInputDialog(
                    "CSS class name to ignore:", "Ignore CSS Class", null
                )?.trim() ?: return@setAddAction
                if (name.isNotBlank() && !ignoredClasses.contains(name)) {
                    synchronized(ignoredClasses) { ignoredClasses.add(name) }
                    model.addElement(name)
                }
            }
            .setRemoveAction {
                list.selectedValuesList.forEach { cls ->
                    synchronized(ignoredClasses) { ignoredClasses.remove(cls) }
                    model.removeElement(cls)
                }
            }
            .createPanel()

        // IntelliJ calls setBorder("Options") on whatever createOptionsPanel() returns.
        // We lock the border after setting our own title so that call is ignored.
        var borderLocked = false
        val panel = object : JPanel(BorderLayout()) {
            override fun setBorder(border: Border?) {
                if (!borderLocked) super.setBorder(border)
            }
        }
        panel.add(decorator)
        panel.border = IdeBorderFactory.createTitledBorder("Ignored Classes", false)
        borderLocked = true
        return panel
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JSElementVisitor() {
            override fun visitJSLiteralExpression(node: JSLiteralExpression) {
                val property = PsiTreeUtil.getParentOfType(node, JSProperty::class.java) ?: return
                if (property.name != "class") return

                val raw = node.text
                val quote = raw.firstOrNull()?.takeIf { it in "'\"" } ?: return
                val content = raw.removePrefix(quote.toString()).removeSuffix(quote.toString())

                val cache = CdnCssCache.getInstance(node.project)
                val known = cache.getLocalClassNames() + cache.getClassNames()
                val ignored = synchronized(ignoredClasses) { ignoredClasses.toHashSet() }

                var i = 0
                while (i < content.length) {
                    while (i < content.length && content[i].isWhitespace()) i++
                    if (i >= content.length) break
                    val start = i
                    while (i < content.length && !content[i].isWhitespace()) i++
                    val token = content.substring(start, i)
                    if (token.isNotEmpty() && token !in known && token !in ignored) {
                        val descriptor = holder.manager.createProblemDescriptor(
                            node,
                            TextRange(1 + start, 1 + i),
                            "Unknown CSS class '$token'",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            isOnTheFly,
                            IgnoreClassFix(token),
                            DisableInspectionFix
                        )
                        holder.registerProblem(descriptor)
                    }
                }
            }
        }

    private inner class IgnoreClassFix(private val className: String) : LocalQuickFix {
        override fun getFamilyName() = "Ignore unknown Kensington CSS class"
        override fun getName() = "Ignore class '$className'"

        override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
            IntentionPreviewInfo.EMPTY

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            synchronized(ignoredClasses) {
                if (!ignoredClasses.contains(className)) ignoredClasses.add(className)
            }
            DaemonCodeAnalyzer.getInstance(project).restart(descriptor.psiElement.containingFile)
        }
    }

    private object DisableInspectionFix : LocalQuickFix {
        override fun getFamilyName() = "Disable 'Unknown CSS class' inspection"

        override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
            IntentionPreviewInfo.EMPTY

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val profile = InspectionProjectProfileManager.getInstance(project).currentProfile as InspectionProfileImpl
            profile.setToolEnabled(SHORT_NAME, false, project)
            profile.profileChanged()
            DaemonCodeAnalyzer.getInstance(project).restart()
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Kensington")
                .createNotification("'Unknown CSS class' inspection disabled", NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimple("Re-enable") {
                    profile.setToolEnabled(SHORT_NAME, true, project)
                    profile.profileChanged()
                    DaemonCodeAnalyzer.getInstance(project).restart()
                })
                .notify(project)
        }
    }

    companion object {
        const val SHORT_NAME = "KensingtonUnknownCssClass"
    }
}
