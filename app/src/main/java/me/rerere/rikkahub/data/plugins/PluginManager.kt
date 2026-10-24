package me.rerere.rikkahub.data.plugins

import android.content.Context
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.time.Duration.Companion.milliseconds

/** 已安装并启用、且允许干预请求的插件。 */
private const val PLUGIN_STATE_FILE = "state.json"
private const val MANIFEST_FILE = "manifest.json"
private const val MAX_ENTRY_BYTES = 4L * 1024 * 1024
private const val HOOK_TIMEOUT_MS = 8_000L
private const val JS_MEMORY_LIMIT_BYTES = 32L * 1024 * 1024

private val PLUGIN_ID_PATTERN = Regex("^[a-zA-Z0-9_.-]{1,64}$")
private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

@Serializable
private data class PluginState(
    val enabled: List<String> = emptyList(),
    val intercept: List<String> = emptyList(),
)

/**
 * 插件管理器。
 *
 * 插件包结构（zip）：
 * ```
 * manifest.json
 * main.js
 * ```
 * 安装时解压到 filesDir/plugins/<id>/；启用状态与拦截授权保存在 state.json。
 *
 * 脚本钩子约定（在 main.js 里定义，均可省略）：
 * - `onAction(actionId)`：按钮 / 快捷动作
 * - `onTool(toolName, argsJson)`：AI 调用插件工具
 * - `onRequest(payload)` / `onResponse(payload)`：返回字符串则替换正文，返回 null 表示不改
 */
class PluginManager(private val context: Context) {

    private val pluginsDir: File get() = File(context.filesDir, "plugins")

    private fun stateFile(): File = File(pluginsDir, PLUGIN_STATE_FILE)

    fun list(): List<InstalledPlugin> {
        val state = readState()
        return pluginsDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val manifest = readManifest(dir) ?: return@mapNotNull null
                InstalledPlugin(
                    manifest = manifest,
                    enabled = manifest.id in state.enabled,
                    interceptEnabled = manifest.id in state.intercept,
                    dirPath = dir.absolutePath,
                )
            }
            .sortedBy { it.manifest.name.lowercase() }
    }

    fun enabledPlugins(): List<InstalledPlugin> = list().filter { it.enabled }

    fun find(id: String): InstalledPlugin? = list().firstOrNull { it.manifest.id == id }

    fun setEnabled(id: String, enabled: Boolean) {
        val state = readState()
        val next = if (enabled) (state.enabled + id).distinct() else state.enabled - id
        writeState(state.copy(enabled = next))
    }

    fun setInterceptEnabled(id: String, enabled: Boolean) {
        val state = readState()
        val next = if (enabled) (state.intercept + id).distinct() else state.intercept - id
        writeState(state.copy(intercept = next))
    }

    fun uninstall(id: String) {
        val dir = File(pluginsDir, id)
        if (dir.canonicalPath.startsWith(pluginsDir.canonicalPath + File.separator)) {
            dir.deleteRecursively()
        }
        val state = readState()
        writeState(state.copy(enabled = state.enabled - id, intercept = state.intercept - id))
    }

    /** 从 zip 安装（或覆盖升级）插件，返回安装结果。 */
    fun install(input: InputStream): Result<PluginManifest> = runCatching {
        val temp = File(context.cacheDir, "plugin-install-${System.currentTimeMillis()}")
        try {
            unzipSafely(input, temp)
            val manifestFile = findManifest(temp)
                ?: error("插件包里没有找到 manifest.json（应放在压缩包根目录）")
            val manifest = json.decodeFromString<PluginManifest>(manifestFile.readText())
            require(PLUGIN_ID_PATTERN.matches(manifest.id)) {
                "插件 id 不合法：${manifest.id}（只允许字母、数字、下划线、短横线、点）"
            }
            require(manifest.name.isNotBlank()) { "插件 name 不能为空" }
            val entry = File(manifestFile.parentFile, manifest.entry)
            require(entry.isFile) { "入口脚本不存在：${manifest.entry}" }
            require(entry.length() <= MAX_ENTRY_BYTES) { "入口脚本过大（上限 4MB）" }

            val target = File(pluginsDir, manifest.id)
            if (target.exists()) target.deleteRecursively()
            check(pluginsDir.isDirectory || pluginsDir.mkdirs()) { "无法创建插件目录" }
            check(manifestFile.parentFile.copyRecursively(target, overwrite = true)) { "写入插件目录失败" }
            // 安装后默认启用，拦截仍需单独授权
            setEnabled(manifest.id, true)
            manifest
        } finally {
            temp.deleteRecursively()
        }
    }

    /** 执行 onAction(actionId)，返回脚本结果（没有该钩子时返回 null）。 */
    suspend fun runAction(pluginId: String, actionId: String): String? =
        runHook(pluginId, "onAction", JsonPrimitive(actionId))

    /** 执行 onTool(toolName, argsJson)，返回脚本结果。 */
    suspend fun runTool(pluginId: String, toolName: String, argsJson: String): String? =
        runHook(pluginId, "onTool", JsonPrimitive(toolName), JsonPrimitive(argsJson))

    /** 执行 onRequest / onResponse，返回替换后的 JSON 文本；null 表示不修改。 */
    suspend fun runIntercept(pluginId: String, hook: String, payloadJson: String): String? =
        runHook(pluginId, hook, JsonPrimitive(payloadJson))

    private suspend fun runHook(pluginId: String, hookName: String, vararg args: JsonPrimitive): String? =
        withContext(Dispatchers.Default) {
            val plugin = find(pluginId) ?: return@withContext null
            val scriptFile = File(plugin.dirPath, plugin.manifest.entry)
            if (!scriptFile.isFile) return@withContext null
            val source = runCatching { scriptFile.readText() }.getOrNull() ?: return@withContext null
            val argList = args.joinToString(", ") { it.toString() }
            val code = """
                (() => {
                  $source
                  const hook = globalThis["$hookName"];
                  if (typeof hook !== 'function') return null;
                  const result = hook($argList);
                  if (result == null) return null;
                  const text = typeof result === 'string' ? result : JSON.stringify(result);
                  return text == null ? null : text.slice(0, 262144);
                })()
            """.trimIndent()
            try {
                withTimeoutOrNull(HOOK_TIMEOUT_MS.milliseconds) {
                    quickJs(Dispatchers.Default) {
                        memoryLimit = JS_MEMORY_LIMIT_BYTES
                        evaluationTimeoutMillis = HOOK_TIMEOUT_MS
                        evaluate<String?>(code)
                    }
                }
            } catch (e: QuickJsException) {
                null
            }
        }

    private fun readManifest(dir: File): PluginManifest? = runCatching {
        val file = File(dir, MANIFEST_FILE)
        if (!file.isFile) return@runCatching null
        json.decodeFromString<PluginManifest>(file.readText())
    }.getOrNull()

    private fun findManifest(root: File): File? {
        root.listFiles().orEmpty().firstOrNull { it.isFile && it.name == MANIFEST_FILE }?.let { return it }
        // 兼容压缩包内多一层目录的常见打包方式
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            File(dir, MANIFEST_FILE).takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    private fun unzipSafely(input: InputStream, target: File) {
        check(target.isDirectory || target.mkdirs()) { "无法创建临时目录" }
        ZipInputStream(input).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith("/") && name.split('/').none { it == ".." }) {
                    "插件包包含非法路径：$name"
                }
                val out = File(target, name)
                require(out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    "插件包包含非法路径：$name"
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n <= 0) break
                            total += n
                            require(total <= 16L * 1024 * 1024) { "插件包过大（上限 16MB）" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun readState(): PluginState = runCatching {
        stateFile().takeIf { it.isFile }
            ?.let { json.decodeFromString<PluginState>(it.readText()) }
            ?: PluginState()
    }.getOrDefault(PluginState())

    private fun writeState(state: PluginState) {
        runCatching {
            pluginsDir.mkdirs()
            stateFile().writeText(json.encodeToString(PluginState.serializer(), state))
        }
    }

    /** 供拦截器使用：启用且授权拦截的插件。 */
    fun interceptPlugins(): List<InstalledPlugin> =
        list().filter { it.enabled && it.interceptEnabled }
}

/** 便捷读取动作输出中的字段（脚本可能返回 JSON 字符串）。 */
fun String?.jsonField(name: String): String? = this?.let {
    runCatching { json.parseToJsonElement(it).jsonObject[name]?.jsonPrimitive?.contentOrNull }.getOrNull()
}

/** 把脚本返回值当作 JSON 对象解析，失败时返回 null。 */
fun String?.asJsonObjectOrNull(): JsonObject? = this?.let {
    runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
}
