package com.kensington.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

internal object CssScanner {
    private val classPattern = Regex("""\.[a-zA-Z_][\w-]*""")
    val cssExtensions = setOf("css", "scss", "less")
    val skipDirs = setOf(".git", ".idea", ".gradle", "build", "out", ".cache")

    fun scanLocalClasses(project: Project): Set<String> {
        val names = mutableSetOf<String>()
        val base = project.guessProjectDir() ?: return names
        VfsUtil.iterateChildrenRecursively(base, { vf ->
            !vf.isDirectory || vf.name !in skipDirs
        }) { vf ->
            if (!vf.isDirectory && vf.extension in cssExtensions) extract(vf, names)
            true
        }
        return names
    }

    private fun extract(file: VirtualFile, into: MutableSet<String>) {
        runCatching {
            classPattern.findAll(String(file.contentsToByteArray()))
                .forEach { into.add(it.value.removePrefix(".")) }
        }
    }
}
