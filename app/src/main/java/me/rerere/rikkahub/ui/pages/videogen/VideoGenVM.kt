package me.rerere.rikkahub.ui.pages.videogen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.videogen.BUILTIN_VIDEO_MODEL
import me.rerere.rikkahub.data.videogen.CancelOutcome
import me.rerere.rikkahub.data.videogen.GeneratedVideo
import me.rerere.rikkahub.data.videogen.PendingVideoTask
import me.rerere.rikkahub.data.videogen.VIDEO_STATUS_CANCELLED
import me.rerere.rikkahub.data.videogen.VIDEO_STATUS_DISPATCHING
import me.rerere.rikkahub.data.videogen.VIDEO_STATUS_FAILED
import me.rerere.rikkahub.data.videogen.VIDEO_STATUS_IDLE
import me.rerere.rikkahub.data.videogen.VIDEO_STATUS_SUCCEEDED
import me.rerere.rikkahub.data.videogen.VIDEO_PROTOCOL_SIFTQ
import me.rerere.rikkahub.data.videogen.VideoGenApi
import me.rerere.rikkahub.data.videogen.VideoGenState
import me.rerere.rikkahub.data.videogen.VideoGenStore
import me.rerere.rikkahub.data.videogen.VideoModel
import me.rerere.rikkahub.data.videogen.VideoParams
import me.rerere.rikkahub.data.videogen.VideoTask
import me.rerere.rikkahub.utils.exportVideoFile
import me.rerere.rikkahub.utils.getActivity
import okhttp3.OkHttpClient
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class VideoGenVM(
    context: Application,
    private val filesManager: FilesManager,
    private val genMediaRepository: GenMediaRepository,
    private val httpClient: OkHttpClient,
) : AndroidViewModel(context) {
    private val store = VideoGenStore(context)

    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<VideoGenState> = _config

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    /** 参考图列表，按用户添加顺序排列（第 1 张为第一张参考图），数量不设上限。 */
    private val _referenceImages = MutableStateFlow<List<String>>(emptyList())
    val referenceImages: StateFlow<List<String>> = _referenceImages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _phase = MutableStateFlow(VIDEO_STATUS_IDLE)
    val phase: StateFlow<String> = _phase

    private val _queuePosition = MutableStateFlow<Int?>(null)
    val queuePosition: StateFlow<Int?> = _queuePosition

    private val _progress = MutableStateFlow<Int?>(null)
    val progress: StateFlow<Int?> = _progress

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds

    private val _currentVideo = MutableStateFlow<GeneratedVideo?>(null)
    val currentVideo: StateFlow<GeneratedVideo?> = _currentVideo

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 通过用户填写的 Base URL + API Key 拉取到的模型列表。 */
    private val _cloudModels = MutableStateFlow<List<String>>(emptyList())
    val cloudModels: StateFlow<List<String>> = _cloudModels

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels

    private val _savedMessage = MutableStateFlow(0L)

    /** 每次成功保存到本机时递增，用于 UI 弹出提示。 */
    val savedMessage: StateFlow<Long> = _savedMessage

    private var cancelJob: Job? = null

    /** 当前正在进行的任务与其模型，用于取消时对接服务端、以及落盘持久化。 */
    private var currentTask: VideoTask? = null
    private var currentModel: VideoModel? = null

    init {
        restorePendingTask()
    }

    val generatedVideos: Flow<PagingData<GeneratedVideo>> = Pager(
        config = PagingConfig(pageSize = 12, enablePlaceholders = false),
        pagingSourceFactory = {
            genMediaRepository.getMediaByTypes(listOf(GenMediaEntity.TYPE_VIDEO_GENERATION))
        }
    ).flow
        .map { pagingData -> pagingData.map { entity -> entity.toGeneratedVideo() } }
        .cachedIn(viewModelScope)

    private fun GenMediaEntity.toGeneratedVideo(): GeneratedVideo {
        val videosDir = filesManager.getVideosDir()
        val fullPath = File(videosDir, path.removePrefix("videos/")).absolutePath
        return GeneratedVideo(
            id = id,
            prompt = prompt,
            filePath = fullPath,
            timestamp = createAt,
            model = modelId,
        )
    }

    // ---- 基础状态 ----

    fun updatePrompt(value: String) {
        _prompt.value = value
    }

    fun clearError() {
        _error.value = null
    }

    /** 追加一张参考图（不设上限，按添加顺序排列）。 */
    fun addReferenceImage(path: String) {
        _referenceImages.value = _referenceImages.value + path
    }

    /** 移除指定参考图，并删除对应的临时文件。 */
    fun removeReferenceImage(path: String) {
        _referenceImages.value = _referenceImages.value - path
        deleteReferenceFile(path)
    }

    fun clearReferenceImages() {
        _referenceImages.value.forEach(::deleteReferenceFile)
        _referenceImages.value = emptyList()
    }

    fun startNewSession() {
        cancelJob?.cancel()
        cancelJob = null
        currentTask = null
        currentModel = null
        store.savePending(null)
        clearReferenceImages()
        _prompt.value = ""
        _currentVideo.value = null
        _error.value = null
        _isGenerating.value = false
        _phase.value = VIDEO_STATUS_IDLE
        _queuePosition.value = null
        _progress.value = null
        _elapsedSeconds.value = 0
    }

    // ---- 模型配置 ----

    fun selectedModel(): VideoModel {
        val state = _config.value
        return state.models.firstOrNull { it.id == state.selectedModelId } ?: BUILTIN_VIDEO_MODEL
    }

    fun selectModel(id: String) {
        updateConfig { it.copy(selectedModelId = id) }
    }

    fun updateParams(params: VideoParams) {
        val state = _config.value
        updateConfig { it.copy(params = it.params + (state.selectedModelId to params)) }
    }

    fun addModel(model: VideoModel) {
        updateConfig { state ->
            state.copy(
                models = state.models + model,
                selectedModelId = model.id,
                params = state.params + (model.id to VideoParams(
                    duration = model.durationOptions.firstOrNull() ?: 6,
                    resolution = model.resolutionOptions.firstOrNull() ?: "768P",
                    fps = model.fpsOptions.firstOrNull() ?: 24,
                    aspect = model.aspectOptions.firstOrNull() ?: "16:9",
                )),
            )
        }
    }

    fun updateModel(model: VideoModel) {
        updateConfig { state ->
            state.copy(models = state.models.map { if (it.id == model.id) model else it })
        }
    }

    fun deleteModel(id: String) {
        if (id == BUILTIN_VIDEO_MODEL.id) return
        updateConfig { state ->
            state.copy(
                models = state.models.filterNot { it.id == id },
                selectedModelId = if (state.selectedModelId == id) BUILTIN_VIDEO_MODEL.id else state.selectedModelId,
                params = state.params - id,
            )
        }
    }

    private fun updateConfig(block: (VideoGenState) -> VideoGenState) {
        val updated = block(_config.value)
        _config.value = updated
        store.save(updated)
    }

    // ---- 云端模型 ----

    fun clearCloudModels() {
        _cloudModels.value = emptyList()
    }

    /** 用当前的接口地址与 API Key 拉取可用模型，供用户挑选。 */
    fun fetchCloudModels(baseUrl: String, apiKey: String) {
        if (_isFetchingModels.value) return
        viewModelScope.launch {
            _isFetchingModels.value = true
            try {
                val models = VideoGenApi.fetchCloudModels(httpClient, baseUrl, apiKey)
                _cloudModels.value = models
                if (models.isEmpty()) {
                    _error.value = "未获取到可用模型"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch cloud models", e)
                _error.value = e.message ?: "获取云端模型失败"
            } finally {
                _isFetchingModels.value = false
            }
        }
    }

    // ---- 生成 ----

    fun generate() {
        val promptText = _prompt.value.trim()
        if (promptText.isBlank() || _isGenerating.value) return
        val state = _config.value
        val model = selectedModel()
        val params = state.paramsFor(model)
        val references = _referenceImages.value.map { File(it) }.filter { it.exists() }

        // 需要参考图的模型（内置免费接口为图生视频，不传图服务端会直接返回 400）必须上传
        if (model.requiresImage && references.isEmpty()) {
            _error.value = "该模型需要参考图，请先上传一张图片"
            return
        }
        if (model.baseUrl.isBlank() && model.protocol != VIDEO_PROTOCOL_SIFTQ) {
            _error.value = "请先在设置里填写该模型的接口地址"
            return
        }

        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _currentVideo.value = null
            _queuePosition.value = null
            _progress.value = null
            _elapsedSeconds.value = 0
            _phase.value = VIDEO_STATUS_DISPATCHING
            try {
                val task = VideoGenApi.submit(
                    client = httpClient,
                    model = model,
                    params = params,
                    prompt = promptText,
                    imageFiles = references,
                )
                currentModel = model
                currentTask = task
                persistPending(params)
                applyTask(task)
                // 提交阶段就失败时直接报错，不再空转轮询
                if (task.status == VIDEO_STATUS_FAILED) {
                    _phase.value = VIDEO_STATUS_FAILED
                    _error.value = task.error ?: "提交失败"
                    return@launch
                }
                // 有些接口是同步返回结果的（提交即有视频地址），直接保存，无需轮询
                if (task.status == VIDEO_STATUS_SUCCEEDED || !task.videoUrl.isNullOrBlank()) {
                    saveResult(model, promptText, params, task)
                    return@launch
                }
                pollUntilDone(model, promptText, params, task)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Video generation failed", e)
                _phase.value = VIDEO_STATUS_FAILED
                _error.value = e.message ?: "生成失败"
            } finally {
                _isGenerating.value = false
                // 任务已结束（成功 / 失败 / 取消），清除未完成快照
                currentTask = null
                currentModel = null
                store.savePending(null)
            }
        }
    }

    /**
     * 取消当前生成。
     *
     * 外置模型会真实调用服务端取消接口；内置免费接口没有取消入口，
     * 只能停止本地轮询，并如实提示用户额度已被消耗。
     */
    fun cancelGeneration() {
        val task = currentTask
        val model = currentModel
        cancelJob?.cancel()
        cancelJob = null
        _isGenerating.value = false
        _phase.value = VIDEO_STATUS_IDLE
        _queuePosition.value = null
        _progress.value = null
        currentTask = null
        currentModel = null
        store.savePending(null)

        if (task == null || model == null || task.taskId.isBlank()) return
        viewModelScope.launch {
            _error.value = when (runCatching { VideoGenApi.cancel(httpClient, model, task) }.getOrNull()) {
                CancelOutcome.CANCELLED -> "已请求服务端取消任务"
                CancelOutcome.UNSUPPORTED ->
                    if (model.protocol == VIDEO_PROTOCOL_SIFTQ) {
                        "已停止本地查询：内置免费接口无法取消，本次免费额度已消耗"
                    } else {
                        "已停止本地查询：该接口不支持取消，服务端任务可能仍在运行"
                    }

                else -> "已停止本地查询，但未能确认服务端已取消"
            }
        }
    }

    /** 把当前进行中的任务写盘，供重启后恢复。 */
    private fun persistPending(params: VideoParams? = null) {
        val task = currentTask
        val model = currentModel
        if (task == null || model == null) {
            store.savePending(null)
            return
        }
        store.savePending(
            PendingVideoTask(
                modelId = model.id,
                prompt = _prompt.value,
                params = params ?: _config.value.paramsFor(model),
                task = task,
                elapsedSeconds = _elapsedSeconds.value,
            ),
        )
    }

    /** 启动时恢复上次未完成的任务并继续轮询。 */
    private fun restorePendingTask() {
        val pending = store.loadPending() ?: return
        val model = _config.value.models.firstOrNull { it.id == pending.modelId }
        if (model == null) {
            store.savePending(null)
            return
        }
        // 已经结束的任务无需恢复
        if (pending.task.status == VIDEO_STATUS_SUCCEEDED ||
            pending.task.status == VIDEO_STATUS_FAILED ||
            pending.task.status == VIDEO_STATUS_CANCELLED
        ) {
            store.savePending(null)
            return
        }
        Log.i(TAG, "Restoring pending video task ${pending.task.taskId}")
        _prompt.value = pending.prompt
        _elapsedSeconds.value = pending.elapsedSeconds
        _isGenerating.value = true
        currentTask = pending.task
        currentModel = model
        applyTask(pending.task)
        cancelJob = viewModelScope.launch {
            try {
                pollUntilDone(model, pending.prompt, pending.params, pending.task)
            } finally {
                _isGenerating.value = false
                currentTask = null
                currentModel = null
                store.savePending(null)
            }
        }
    }

    private fun applyTask(task: VideoTask) {
        _phase.value = task.status
        _queuePosition.value = task.queuePosition
        _progress.value = task.progress
    }

    /** 每 1 秒查询一次任务进度，直到成功、失败或超时。 */
    private suspend fun pollUntilDone(
        model: VideoModel,
        prompt: String,
        params: VideoParams,
        initialTask: VideoTask,
    ) {
        var task = initialTask
        var previous = initialTask
        var consecutiveErrors = 0
        while (true) {
            delay(POLL_INTERVAL_MS)
            _elapsedSeconds.value += 1
            val updated = try {
                VideoGenApi.query(httpClient, model, task).also { consecutiveErrors = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 单次网络抖动不应直接判定失败，连续多次失败才放弃
                consecutiveErrors++
                Log.e(TAG, "Polling failed ($consecutiveErrors)", e)
                if (consecutiveErrors >= MAX_POLL_ERRORS) {
                    _phase.value = VIDEO_STATUS_FAILED
                    _error.value = e.message ?: "查询进度失败"
                    return
                }
                null
            }
            if (updated == null) {
                if (_elapsedSeconds.value >= MAX_POLL_SECONDS) {
                    _phase.value = VIDEO_STATUS_FAILED
                    _error.value = "生成超时，请稍后重试"
                    return
                }
                continue
            }
            task = updated
            applyTask(task)
            // 状态有变化时刷新落盘快照，便于重启后从当前位置继续
            if (updated.status != previous.status ||
                updated.queuePosition != previous.queuePosition ||
                updated.progress != previous.progress
            ) {
                persistPending(params)
            }
            previous = updated
            when (task.status) {
                VIDEO_STATUS_SUCCEEDED -> {
                    saveResult(model, prompt, params, task)
                    return
                }

                VIDEO_STATUS_FAILED, VIDEO_STATUS_CANCELLED -> {
                    _error.value = task.error ?: "生成失败"
                    return
                }

                else -> if (_elapsedSeconds.value >= MAX_POLL_SECONDS) {
                    _phase.value = VIDEO_STATUS_FAILED
                    _error.value = "生成超时，请稍后重试"
                    return
                }
            }
        }
    }

    private suspend fun saveResult(
        model: VideoModel,
        prompt: String,
        params: VideoParams,
        task: VideoTask,
    ) {
        val videosDir = filesManager.getVideosDir()
        val timestamp = System.currentTimeMillis()
        val safeName = model.name.replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_")
        val fileName = "${timestamp}_$safeName.mp4"
        val target = File(videosDir, fileName)

        try {
            VideoGenApi.download(httpClient, model, task, target)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download video", e)
            _phase.value = VIDEO_STATUS_FAILED
            _error.value = e.message ?: "下载视频失败"
            return
        }

        val entity = GenMediaEntity(
            path = "videos/$fileName",
            modelId = model.name,
            prompt = prompt,
            createAt = timestamp,
            type = GenMediaEntity.TYPE_VIDEO_GENERATION,
            sourcePaths = "${params.duration}s · ${params.resolution} · ${params.fps}fps · ${params.aspect}",
        )
        val id = genMediaRepository.insertMedia(entity)
        _currentVideo.value = GeneratedVideo(
            id = id.toInt(),
            prompt = prompt,
            filePath = target.absolutePath,
            timestamp = timestamp,
            model = model.name,
        )
        _phase.value = VIDEO_STATUS_SUCCEEDED
    }

    // ---- 结果管理 ----

    fun deleteVideo(video: GeneratedVideo) {
        viewModelScope.launch {
            try {
                genMediaRepository.deleteMedia(video.id)
                val file = File(video.filePath)
                if (file.exists()) file.delete()
                if (_currentVideo.value?.id == video.id) {
                    _currentVideo.value = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete video", e)
                _error.value = "删除失败"
            }
        }
    }

    fun saveVideoToDevice(video: GeneratedVideo) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val activity = app.getActivity() ?: error("Activity not found")
                withContext(Dispatchers.IO) {
                    app.exportVideoFile(activity, File(video.filePath))
                }
                _error.value = null
                _savedMessage.value = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save video", e)
                _error.value = e.message ?: "保存失败"
            }
        }
    }

    private fun deleteReferenceFile(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    companion object {
        private const val TAG = "VideoGenVM"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val MAX_POLL_SECONDS = 600

        /** 轮询时允许的连续网络错误次数，超过才判定失败。 */
        private const val MAX_POLL_ERRORS = 6
    }
}