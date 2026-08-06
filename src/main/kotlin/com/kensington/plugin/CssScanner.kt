package com.kensington.plugin

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

internal object CssScanner {
    private val classPattern = Regex("""\.[a-zA-Z_][\w-]*""")
    val cssExtensions = setOf("css", "scss", "less")

    fun scanLocalClasses(project: Project): Set<String> = scanWithSources(project).keys

    /**
     * Extracts class names from an explicit list of CSS files, mapping each name to the
     * first file that defines it. Used for CSS files discovered outside the normal project
     * scan (e.g. resolved via node_modules).
     */
    fun scanClassesFromFiles(project: Project, files: List<VirtualFile>): Map<String, VirtualFile> {
        val result = mutableMapOf<String, VirtualFile>()
        for (file in files) {
            ProgressManager.checkCanceled()
            val bytes = ProjectFileScanner.readBytes(project, file) ?: continue
            val content = stripNoise(String(bytes))
            classPattern.findAll(content).forEach { match ->
                ProgressManager.checkCanceled()
                val name = match.value.removePrefix(".")
                if (name !in result) result[name] = file
            }
        }
        return result
    }

    /**
     * Returns class name → first VirtualFile that defines it. File discovery and content reads use
     * cancellable read actions; regex processing runs without the application read lock.
     */
    fun scanWithSources(project: Project): Map<String, VirtualFile> {
        val result = mutableMapOf<String, VirtualFile>()
        for (file in ProjectFileScanner.collectFiles(project, cssExtensions)) {
            ProgressManager.checkCanceled()
            val bytes = ProjectFileScanner.readBytes(project, file) ?: continue
            val content = stripNoise(String(bytes))
            classPattern.findAll(content).forEach { match ->
                ProgressManager.checkCanceled()
                val name = match.value.removePrefix(".")
                if (name !in result) {
                    result[name] = file
                }
            }
        }
        return result
    }

    /**
     * Returns the character offset of `.$className` in the raw file content,
     * positioned just after the leading dot so navigation lands on the identifier.
     * Must be called inside a read action.
     */
    fun findClassOffset(vf: VirtualFile, className: String): Int? =
        runCatching {
            val raw = String(vf.contentsToByteArray())
            Regex("""\.$className(?![a-zA-Z0-9_-])""").find(raw)?.range?.first?.plus(1)
        }.getOrNull()

    // Strip block comments and quoted strings to avoid false-positive class name matches
    // inside property values or string literals.
    private fun stripNoise(content: String): String = content
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex(""""[^"\\]*(?:\\.[^"\\]*)*""""), "")
        .replace(Regex("""'[^'\\]*(?:\\.[^'\\]*)*'"""), "")
}
