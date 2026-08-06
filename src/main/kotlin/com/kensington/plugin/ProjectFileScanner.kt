package com.kensington.plugin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Collects project files without holding a non-cancellable application read lock.
 *
 * Kensington scans run from background tasks. A write action requested by the EDT cancels and
 * retries these non-blocking read actions, allowing the write action to proceed without freezing
 * the UI. Directory filtering is intentionally applied during traversal so dependency trees are
 * never loaded merely to reject their files later.
 */
internal object ProjectFileScanner {
    private const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024

    private val skippedDirectories = setOf(
        ".git",
        ".idea",
        ".gradle",
        ".cache",
        ".intellijPlatform",
        "node_modules",
        "build",
        "out",
        "coverage"
    )

    fun collectFiles(project: Project, extensions: Set<String>): List<VirtualFile> =
        ReadAction.nonBlocking<List<VirtualFile>> {
            val fileIndex = ProjectRootManager.getInstance(project).fileIndex
            val files = LinkedHashSet<VirtualFile>()

            for (root in ProjectRootManager.getInstance(project).contentRoots) {
                ProgressManager.checkCanceled()
                if (!root.isValid) continue

                VfsUtil.iterateChildrenRecursively(root, { file ->
                    ProgressManager.checkCanceled()
                    !file.isDirectory ||
                        (file.name !in skippedDirectories &&
                            fileIndex.isInContent(file) &&
                            !fileIndex.isExcluded(file))
                }) { file ->
                    ProgressManager.checkCanceled()
                    if (!file.isDirectory && file.extension?.lowercase() in extensions) {
                        files.add(file)
                    }
                    true
                }
            }

            files.toList()
        }
            .expireWith(project)
            .executeSynchronously()

    /**
     * Finds CSS files anywhere under the content roots — including `node_modules` and other
     * normally-skipped directories — that match any of the given path suffixes (e.g.
     * `vendor/bootstrap/css/bootstrap.min.css`). Used to resolve non-CDN link references
     * that a framework serves out of `node_modules` via a static-mount rewrite.
     *
     * Matching is progressive: for each suffix we first look for files whose path ends with
     * the full tail, then drop the leading path segment and retry, all the way down to a
     * bare filename match. This handles cases like `express.static('/vendor/bootstrap',
     * 'node_modules/bootstrap/dist')` where the URL prefix doesn't correspond to any real
     * directory on disk. All matches at the deepest matching level are returned.
     *
     * Filters by filename first so we don't slurp every file under node_modules.
     */
    fun collectCssFilesBySuffix(project: Project, suffixes: Set<String>): List<VirtualFile> {
        if (suffixes.isEmpty()) return emptyList()
        val filenames = suffixes.map { it.substringAfterLast('/').lowercase() }.toSet()
        return ReadAction.nonBlocking<List<VirtualFile>> {
            val candidatesByName = mutableMapOf<String, MutableList<VirtualFile>>()
            for (root in ProjectRootManager.getInstance(project).contentRoots) {
                ProgressManager.checkCanceled()
                if (!root.isValid) continue

                VfsUtil.iterateChildrenRecursively(root, { file ->
                    ProgressManager.checkCanceled()
                    !file.isDirectory || file.name !in unrestrictedSkippedDirectories
                }) { file ->
                    ProgressManager.checkCanceled()
                    if (!file.isDirectory &&
                        file.extension?.lowercase() == "css" &&
                        file.name.lowercase() in filenames) {
                        candidatesByName.getOrPut(file.name.lowercase()) { mutableListOf() }.add(file)
                    }
                    true
                }
            }

            val results = LinkedHashSet<VirtualFile>()
            for (suffix in suffixes) {
                val name = suffix.substringAfterLast('/').lowercase()
                val candidates = candidatesByName[name] ?: continue
                var remaining = suffix
                while (true) {
                    ProgressManager.checkCanceled()
                    val matches = candidates.filter { it.path.endsWith("/$remaining") }
                    if (matches.isNotEmpty()) {
                        results.addAll(matches)
                        break
                    }
                    val slashIdx = remaining.indexOf('/')
                    if (slashIdx < 0) {
                        // No more segments to drop; accept every same-named file as a match.
                        results.addAll(candidates)
                        break
                    }
                    remaining = remaining.substring(slashIdx + 1)
                }
            }
            results.toList()
        }
            .expireWith(project)
            .executeSynchronously()
    }

    // Excludes only VCS/IDE metadata — node_modules et al. are intentionally traversed here
    // because the referenced CSS often lives inside them.
    private val unrestrictedSkippedDirectories = setOf(".git", ".idea", ".gradle", ".intellijPlatform")

    /**
     * Copies file contents under a cancellable read action. Parsing and regex work must happen
     * after this method returns so it never extends the lifetime of the application read lock.
     */
    fun readBytes(project: Project, file: VirtualFile): ByteArray? =
        ReadAction.nonBlocking<ByteArray?> {
            ProgressManager.checkCanceled()
            if (!file.isValid || file.length > MAX_FILE_SIZE_BYTES) {
                null
            } else {
                try {
                    file.contentsToByteArray()
                } catch (_: IOException) {
                    null
                }
            }
        }
            .expireWith(project)
            .executeSynchronously()
}
