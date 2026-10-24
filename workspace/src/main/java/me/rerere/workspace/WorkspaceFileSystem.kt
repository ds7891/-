package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val dir = resolvePath(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(config.maxListEntries)
            .map { it.toEntry(root) }
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        return file.readText(charset)
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolvePath(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.toEntry(root)
    }

    fun importBytes(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry {
        val file = resolvePath(root, path)
        file.parentFile?.mkdirs()
        val target = if (!file.exists()) file else resolveConflict(file)
        inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target.toEntry(root)
    }

    private fun resolveConflict(file: File): File {
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do { candidate = File(file.parentFile, "$stem ($n)$ext"); n++ } while (candidate.exists())
        return candidate
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = resolvePath(root, path)
        if (!file.exists()) return false
        return if (file.isDirectory) {
            require(recursive) { "Directory delete requires recursive = true" }
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
            if (targetFile.isDirectory) {
                targetFile.deleteRecursively()
            } else {
                targetFile.delete()
            }
        }
        targetFile.parentFile?.mkdirs()
        require(sourceFile.renameTo(targetFile)) {
            "Failed to move $source to $target"
        }
        return targetFile.toEntry(root)
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        return walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .filter { matcher.matches(root.toPath().relativize(it).normalizeForMatch()) }
                .take(config.maxListEntries)
                .map { it.toFile().toEntry(root) }
                .toList()
        }
    }

    /**
     * 递归搜索文件名。给 UI 的搜索框用，比 [glob] 更贴合直觉：
     * 直接输 `main` 就能命中，不需要写成 `*main*`。
     *
     * 规则：
     * - query 里带 `*` 或 `?` 时按 glob 匹配相对路径；纯文本时按「忽略大小写包含」匹配文件名；
     * - 排序按 精确 > 前缀 > 后缀 > 包含，同样权重再按名字，让最像的排最前；
     * - 跳过 `.l2s.` 临时文件与搜索起点自身，与 [list] / [glob] 行为一致；
     * - 遍历条目数超过 [WorkspaceConfig.maxSearchVisit] 或命中数超过 [WorkspaceConfig.maxSearchResults]
     *   时停止并置 `truncated`，避免在整棵 rootfs 上无界扫描。
     */
    fun search(
        root: File,
        query: String,
        path: String = "",
        includeDirectories: Boolean = true,
        limit: Int = config.maxSearchResults,
    ): WorkspaceFileSearchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return WorkspaceFileSearchResult()
        val start = resolvePath(root, path)
        if (!start.exists()) return WorkspaceFileSearchResult()

        val hasWildcard = trimmed.any { it == '*' || it == '?' }
        val globMatcher = if (hasWildcard) {
            val pattern = if (trimmed.contains('/')) trimmed else "**/$trimmed"
            runCatching { FileSystems.getDefault().getPathMatcher("glob:$pattern") }.getOrNull()
        } else null
        val needle = trimmed.lowercase()
        val startPath = start.toPath()
        val rootPath = root.toPath()

        val matched = mutableListOf<Pair<File, Int>>()
        var visited = 0
        var truncated = false
        Files.walk(startPath).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val current = iterator.next()
                if (visited >= config.maxSearchVisit) {
                    truncated = true
                    break
                }
                visited++
                if (current == startPath) continue
                val relative = runCatching { rootPath.relativize(current) }.getOrNull() ?: continue
                // 临时条目要整棵剪掉：只过滤文件名的话，.l2s.tmp/ 里面的文件仍然会被搜出来
                if (relative.any { it.toString().startsWith(TEMP_ENTRY_PREFIX) }) continue
                val file = current.toFile()
                val isDir = file.isDirectory
                if (isDir && !includeDirectories) continue
                if (!isDir && !file.isFile) continue
                val hit = if (globMatcher != null) {
                    runCatching { globMatcher.matches(relative.normalizeForMatch()) }.getOrDefault(false)
                } else {
                    file.name.lowercase().contains(needle)
                }
                if (!hit) continue
                matched += file to nameRank(file.name, needle)
                if (matched.size >= limit * 4) {
                    // 先多攒一点再排序截断，否则「排在前面的」只是磁盘顺序而不是相关度
                    truncated = true
                    break
                }
            }
        }

        val entries = matched
            .sortedWith(compareBy({ it.second }, { it.first.name.lowercase() }))
            .take(limit)
            .map { it.first.toEntry(root) }
        return WorkspaceFileSearchResult(
            entries = entries,
            truncated = truncated || matched.size > limit,
            visited = visited,
        )
    }

    /**
     * 相关度权重，0 最像。
     *
     * 文件名与「去掉扩展名的词干」各算一次取较小值，这样搜 `report` 时
     * `report.txt` 排第一（词干精确命中），而不是被 `report-2024.txt` 这种
     * 靠前缀匹配抢走位置 —— 纯按文件名算的话两者都是「前缀匹配」，分不出主次。
     */
    private fun nameRank(name: String, needle: String): Int {
        val lower = name.lowercase()
        val stem = lower.substringBeforeLast('.', lower)
        fun rankOf(candidate: String) = when {
            candidate == needle -> 0
            candidate.startsWith(needle) -> 1
            candidate.endsWith(needle) -> 2
            else -> 3
        }
        return minOf(rankOf(lower), rankOf(stem))
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val results = mutableListOf<WorkspaceSearchMatch>()
        walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .forEach { path ->
                    if (results.size >= config.maxSearchResults) return@forEach
                    if (includeMatcher != null &&
                        !includeMatcher.matches(root.toPath().relativize(path).normalizeForMatch())
                    ) {
                        return@forEach
                    }
                    val file = path.toFile()
                    if (file.length() > config.maxReadBytes) return@forEach
                    file.useLines(StandardCharsets.UTF_8) { lines ->
                        lines.forEachIndexed { index, line ->
                            if (results.size >= config.maxSearchResults) return@useLines
                            if (matcher.containsMatchIn(line)) {
                                results += WorkspaceSearchMatch(
                                    path = file.relativePath(root),
                                    line = index + 1,
                                    text = line,
                                )
                            }
                        }
                    }
                }
        }
        return results
    }

    private fun <T> walk(start: File, block: (Sequence<Path>) -> T): T =
        Files.walk(start.toPath()).use { stream ->
            block(stream.iterator().asSequence())
        }

    private fun resolvePath(root: File, path: String): File {
        root.mkdirs()
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        return target
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativePath(root),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    private fun File.relativePath(root: File): String {
        val rootCanonical = root.canonicalFile
        val parentCanonical = (parentFile ?: rootCanonical).canonicalFile
        return File(parentCanonical, name).relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
    }

    private fun Path.normalizeForMatch(): Path =
        FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String =
        joinToString("/") { it.name }

    private companion object {
        /** 工作区内部临时条目的名字前缀：搜索与列表都不该看见它，连同它的整棵子树一起剪掉。 */
        const val TEMP_ENTRY_PREFIX = ".l2s."
    }
}
