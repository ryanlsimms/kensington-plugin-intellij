package com.kensington.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class CdnCssCache(private val project: Project) : Disposable {

    private val log = thisLogger()
    private val classPattern = Regex("""\.[a-zA-Z_][\w-]*""")
    private val cdnCssUrlPattern = Regex("""https?://\S+\.css(?:[?#][^\s"')]*)?""")
    // Matches path-like references ending in `.css` (word chars, `/`, `.`, `-`). Runs on
    // content that has already had CDN URLs stripped so we don't re-catch remnants of those.
    private val localCssPathPattern = Regex("""[\w./\-]+\.css""")
    private val scanExtensions = setOf("html", "htm", "css", "scss", "less", "js", "ts", "mjs", "cjs")

    private val cacheDir: Path = Paths.get(PathManager.getSystemPath(), "kensington", "cdn-css")
    @Volatile private var classNames: Set<String> = emptySet()
    /** Class names extracted from CSS files resolved via non-CDN link references (e.g. node_modules). */
    @Volatile private var externalClassNames: Set<String> = emptySet()
    /** class name → first external CSS file that defines it, for go-to-definition */
    @Volatile private var externalClassFiles: Map<String, VirtualFile> = emptyMap()
    @Volatile private var localClassNames: Set<String> = emptySet()
    /** class name → display name of the local file that first defines it */
    @Volatile private var localClassSources: Map<String, String> = emptyMap()
    /** class name → VirtualFile that first defines it, for go-to-definition */
    @Volatile private var localClassFiles: Map<String, VirtualFile> = emptyMap()
    private val refreshing = AtomicBoolean(false)

    init {
        // All disk and project scans happen asynchronously via triggerRefresh(). Project services
        // can be constructed on the EDT, so their constructors must not perform file I/O.

        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val projectPath = project.basePath ?: return
                val relevant = events.any { event ->
                    val file = event.file ?: return@any false
                    file.extension?.lowercase() in scanExtensions &&
                        (file.path == projectPath || file.path.startsWith("$projectPath/"))
                }
                if (relevant) triggerRefresh()
            }
        })
    }

    override fun dispose() {}

    fun getClassNames(): Set<String> = classNames + externalClassNames
    fun getLocalClassNames(): Set<String> = localClassNames
    /** Maps each locally-defined class name to the filename it was first found in. */
    fun getLocalClassSources(): Map<String, String> = localClassSources
    /** Returns the VirtualFile that first defines the given class, or null if unknown/CDN-only. */
    fun getLocalClassFile(className: String): VirtualFile? = localClassFiles[className]
    /**
     * Returns the VirtualFile that defines the given class, checking local project CSS first,
     * then external CSS files resolved via link references (e.g. `node_modules`).
     */
    fun getClassFile(className: String): VirtualFile? =
        localClassFiles[className] ?: externalClassFiles[className]

    fun triggerRefresh() {
        if (refreshing.compareAndSet(false, true)) {
            object : Task.Backgroundable(project, "Kensington: updating CDN styles") {
                override fun run(indicator: ProgressIndicator) {
                    try { refresh(indicator) } finally { refreshing.set(false) }
                }
            }.queue()
        }
    }

    fun refresh(indicator: ProgressIndicator? = null) {
        Files.createDirectories(cacheDir)
        classNames = loadFromDisk()

        val (urls, localRefs) = detectCssReferences()
        val expectedFiles = urls.map { urlToFileName(it) }.toSet()

        var changed = Files.list(cacheDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".css") }
                .filter { it.fileName.toString() !in expectedFiles }
                .map { Files.deleteIfExists(it) }
                .anyMatch { it }
        }

        indicator?.isIndeterminate = false
        urls.forEachIndexed { i, url ->
            ProgressManager.checkCanceled()
            indicator?.fraction = i.toDouble() / urls.size
            indicator?.text2 = url
            if (fetchIfStale(url)) changed = true
        }
        indicator?.fraction = 1.0
        if (changed) classNames = loadFromDisk()
        val sources = CssScanner.scanWithSources(project)
        localClassNames = sources.keys
        localClassSources = sources.mapValues { (_, vf) -> vf.name }
        localClassFiles = sources

        val resolved = ProjectFileScanner.collectCssFilesBySuffix(project, localRefs)
        val externalSources = if (resolved.isEmpty()) emptyMap()
                              else CssScanner.scanClassesFromFiles(project, resolved)
        externalClassFiles = externalSources
        externalClassNames = externalSources.keys
    }

    private fun loadFromDisk(): Set<String> {
        val names = mutableSetOf<String>()
        Files.list(cacheDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".css") }.forEach { file ->
                runCatching {
                    classPattern.findAll(Files.readString(file))
                        .forEach { names.add(it.value.removePrefix(".")) }
                }
            }
        }
        return names
    }

    private fun detectCssReferences(): Pair<Set<String>, Set<String>> {
        val urls = mutableSetOf<String>()
        val localPaths = mutableSetOf<String>()
        val leadingRelative = Regex("""^(\.\.?/)+""")
        for (file in ProjectFileScanner.collectFiles(project, scanExtensions)) {
            ProgressManager.checkCanceled()
            val bytes = ProjectFileScanner.readBytes(project, file) ?: continue
            val content = stripComments(String(bytes), file.extension?.lowercase())
            cdnCssUrlPattern.findAll(content).forEach {
                ProgressManager.checkCanceled()
                urls.add(it.value)
            }
            val nonCdn = cdnCssUrlPattern.replace(content, "")
            localCssPathPattern.findAll(nonCdn).forEach { match ->
                ProgressManager.checkCanceled()
                val normalized = match.value
                    .substringBefore('?')
                    .substringBefore('#')
                    .trimStart('/')
                    .let { leadingRelative.replace(it, "") }
                // Require at least one path separator: bare filenames collide too easily.
                if (normalized.contains('/') && normalized.endsWith(".css")) {
                    localPaths.add(normalized)
                }
            }
        }
        return urls to localPaths
    }

    private fun stripComments(content: String, extension: String?): String = when (extension) {
        "html", "htm" -> content.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        "css", "scss", "less" -> content.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        "js", "ts", "mjs", "cjs" -> content
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(?<!:)//[^\n]*"""), "")
        else -> content
    }

    private fun fetchIfStale(url: String): Boolean {
        val file = cacheDir.resolve(urlToFileName(url))
        val stale = !Files.exists(file) ||
            System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() > CACHE_TTL_MS
        if (!stale) return false
        return runCatching {
            val conn = URI(url).toURL().openConnection()
            conn.connectTimeout = 5_000
            conn.readTimeout = 15_000
            Files.writeString(file, conn.getInputStream().bufferedReader().readText())
            log.info("Kensington: cached CDN CSS from $url")
            true
        }.getOrElse { e ->
            log.warn("Kensington: failed to fetch $url — ${e.message}")
            false
        }
    }

    private fun urlToFileName(url: String): String {
        val hash = MessageDigest.getInstance("MD5").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$hash.css"
    }

    companion object {
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        fun getInstance(project: Project): CdnCssCache = project.getService(CdnCssCache::class.java)
    }
}
