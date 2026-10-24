package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceFileSearchResult
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceSearchMatch
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    /** 当前挂起的搜索任务，用于防抖与取消过期扫描。 */
    private var searchJob: Job? = null

    private var pendingExport: Pair<WorkspaceStorageArea, List<WorkspaceFileEntry>>? = null

    fun prepareBatchExport(entries: List<WorkspaceFileEntry>): Boolean {
        val files = entries.filterNot { it.isDirectory }
        if (pendingExport != null || state.value.exporting || files.isEmpty()) return false
        pendingExport = state.value.area to files
        return true
    }

    fun dismissExportResult() {
        _state.update { it.copy(exportResult = null) }
    }

    fun exportFilesToDirectory(treeUri: Uri?, resolver: ContentResolver) {
        val (area, entries) = pendingExport.also { pendingExport = null } ?: return
        if (treeUri == null) return
        _state.update { it.copy(exporting = true, exportCompleted = 0, exportTotal = entries.size, exportResult = null) }
        viewModelScope.launch {
            var succeeded = 0
            val failures = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    entries.forEachIndexed { index, entry ->
                        ensureActive()
                        var destination: Uri? = null
                        try {
                            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                entry.name.substringAfterLast('.', "").lowercase()
                            ) ?: "application/octet-stream"
                            val document = DocumentsContract.createDocument(resolver, parent, mime, entry.name)
                                ?: error("无法创建目标文件")
                            destination = document
                            val output = resolver.openOutputStream(document) ?: error("无法打开目标文件")
                            output.use { repository.exportFile(id, area, entry.path, it) }
                            succeeded++
                            destination = null
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failures += "${entry.name}：${error.message ?: "导出失败"}"
                        } finally {
                            // 只清理本次创建但未完整写入的文件。
                            destination?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                        }
                        _state.update { it.copy(exportCompleted = index + 1) }
                    }
                }
                _state.update {
                    it.copy(exportResult = buildString {
                        append("已导出 $succeeded/${entries.size} 个文件")
                        if (failures.isNotEmpty()) append("\n\n" + failures.joinToString("\n"))
                    })
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(exportResult = "导出失败：${error.message}") }
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError = _settingsError.asStateFlow()

    fun dismissSettingsError() {
        _settingsError.value = null
    }

    init {
        loadWorkspace()
        refresh()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        cancelSearch()
        _state.update { it.copy(area = area, path = "").clearedForNavigation() }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        cancelSearch()
        _state.update { it.copy(path = entry.path).clearedForNavigation() }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        cancelSearch()
        _state.update {
            it.copy(path = path.substringBeforeLast('/', missingDelimiterValue = "")).clearedForNavigation()
        }
        refresh()
    }

    // ------------------------------------------------------------------
    // 搜索
    // ------------------------------------------------------------------

    /** 打字触发：等 [SEARCH_DEBOUNCE_MS] 再扫盘，避免每个字符都遍历一次目录树。 */
    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            clearSearch()
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch()
        }
    }

    /** 回车 / 点重试：不等防抖，立刻搜。 */
    fun submitSearch() {
        searchJob?.cancel()
        runSearch()
    }

    fun setSearchMode(mode: WorkspaceSearchMode) {
        if (_state.value.searchMode == mode) return
        _state.update { it.copy(searchMode = mode) }
        if (_state.value.searchQuery.isNotBlank()) submitSearch()
    }

    fun setSearchWholeArea(enabled: Boolean) {
        if (_state.value.searchWholeArea == enabled) return
        _state.update { it.copy(searchWholeArea = enabled) }
        if (_state.value.searchQuery.isNotBlank()) submitSearch()
    }

    fun clearSearch() {
        cancelSearch()
        _state.update {
            it.copy(
                searchQuery = "",
                searchResults = emptyList(),
                searchMatches = emptyList(),
                searching = false,
                searchTruncated = false,
                searchError = null,
            )
        }
    }

    private fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
    }

    private fun runSearch() {
        cancelSearch()
        val snapshot = _state.value
        val query = snapshot.searchQuery.trim()
        if (query.isEmpty()) {
            _state.update {
                it.copy(
                    searchResults = emptyList(),
                    searchMatches = emptyList(),
                    searching = false,
                    searchTruncated = false,
                    searchError = null,
                )
            }
            return
        }
        val area = snapshot.area
        val mode = snapshot.searchMode
        // 范围：整个区域从根开始；只搜当前目录则限定在 path 之下
        val scopePath = if (snapshot.searchWholeArea) "" else snapshot.path
        _state.update { it.copy(searching = true, searchError = null) }
        searchJob = viewModelScope.launch {
            val result = try {
                when (mode) {
                    WorkspaceSearchMode.NAME ->
                        repository.searchFiles(id, area, scopePath, query) to emptyList<WorkspaceSearchMatch>()

                    WorkspaceSearchMode.CONTENT ->
                        WorkspaceFileSearchResult() to repository.grepFiles(id, area, scopePath, query)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        searching = false,
                        searchError = error.message ?: "搜索失败",
                    )
                }
                return@launch
            }
            val (fileResult, matches) = result
            // 结果回来时如果区域 / 目录 / 关键词已经变了，说明这是一次过期的扫描，直接丢弃，
            // 否则慢的那次会把新目录的结果覆盖掉。
            val current = _state.value
            if (current.area != area || current.path != snapshot.path ||
                current.searchQuery.trim() != query || current.searchMode != mode
            ) {
                return@launch
            }
            _state.update {
                it.copy(
                    searching = false,
                    searchResults = fileResult.entries,
                    searchMatches = matches,
                    searchTruncated = fileResult.truncated,
                    searchError = null,
                )
            }
        }
    }

    /** 换目录 / 换区域后带走的干净状态：清掉列表与搜索痕迹。 */
    private fun WorkspaceDetailState.clearedForNavigation(): WorkspaceDetailState = copy(
        entries = emptyList(),
        error = null,
        searchQuery = "",
        searchResults = emptyList(),
        searchMatches = emptyList(),
        searching = false,
        searchTruncated = false,
        searchError = null,
    )

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { entries ->
                _state.update { it.copy(entries = entries, loading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    suspend fun resolveImageFile(
        entry: WorkspaceFileEntry,
        area: WorkspaceStorageArea,
    ): File = repository.resolveFile(id, area, entry.path)

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    fun setShellCompatibilityMode(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setShellCompatibilityMode(id, enabled)
                val workspace = repository.getById(id)
                _state.update { it.copy(workspace = workspace) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _settingsError.value = error.message.orEmpty()
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
        }
    }

    private companion object {
        /**
         * 打字停止多久后才真正扫盘。太短会每敲一个字遍历一次目录树（Linux 区几万文件会明显发热），
         * 太长手感发黏。280ms 是两者之间比较舒服的位置。
         */
        const val SEARCH_DEBOUNCE_MS = 280L
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val exporting: Boolean = false,
    val exportCompleted: Int = 0,
    val exportTotal: Int = 0,
    val exportResult: String? = null,
    // ---- 搜索 ----
    val searchQuery: String = "",
    val searchMode: WorkspaceSearchMode = WorkspaceSearchMode.NAME,
    /** true = 搜整个区域（含所有子目录）；false = 只搜当前目录下面 */
    val searchWholeArea: Boolean = true,
    val searchResults: List<WorkspaceFileEntry> = emptyList(),
    val searchMatches: List<WorkspaceSearchMatch> = emptyList(),
    val searching: Boolean = false,
    /** 命中过多或遍历到上限，结果只是前一部分 */
    val searchTruncated: Boolean = false,
    val searchError: String? = null,
) {
    /** 搜索框里有内容就算处于搜索态，列表改渲染结果而不是目录内容。 */
    val searchActive: Boolean get() = searchQuery.isNotBlank()
}

/** 搜索目标：文件名，或文件内容。 */
enum class WorkspaceSearchMode {
    NAME,
    CONTENT,
}

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}
