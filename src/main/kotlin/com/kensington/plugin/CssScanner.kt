package com.kensington.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

internal object CssScanner {
    private val classPattern = Regex("""\.[a-zA-Z_][\w-]*""")
    val cssExtensions = setOf("css", "scss", "less")
    val skipDirs = setOf(".git", ".idea", ".gradle", "build", "out", ".cache")

    fun scanLocalClasses(project: Project): Set<String> = scanWithSources(project).keys

    /** Returns class name → first VirtualFile that defines it. Must be called inside a read action. */
    fun scanWithSources(project: Project): Map<String, VirtualFile> {
        val result = mutableMapOf<String, VirtualFile>()
        val base = project.guessProjectDir() ?: return result
        VfsUtil.iterateChildrenRecursively(base, { vf ->
            !vf.isDirectory || vf.name !in skipDirs
        }) { vf ->
            if (!vf.isDirectory && vf.extension in cssExtensions) {
                runCatching {
                    val content = stripNoise(String(vf.contentsToByteArray()))
                    classPattern.findAll(content).forEach { match ->
                        val name = match.value.removePrefix(".")
                        if (name !in result) result[name] = vf
                    }
                }
            }
            true
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
