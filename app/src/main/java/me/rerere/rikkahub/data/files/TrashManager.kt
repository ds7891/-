package me.rerere.rikkahub.data.files

import android.content.Context
import android.os.Environment
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.uuid.Uuid

/** 「最近删除」里的一条记录。 */
@Serializable
data class TrashEntry(
    val id: String,
    /** 原文件名 */
    val name: String,
    /** 删除前的绝对路径，恢复时写回这里 */
    val originalPath: String,
    /** 移入回收站后的实际存放路径 */
    val storedPath: String,
    /** 删除时间（epoch 毫秒） */
    val deletedAt: Long,
    /** 文件大小；目录记为 -1 */
    val sizeBytes: Long,
    val isDirectory: Boolean,
)

/**
 * 「最近删除」回收站。
 *
 * AI 通过 file_system 工具的 delete 动作删除的文件不会立即销毁，而是先移动到这里；
 * 用户可在对话页「+ → 最近删除」里恢复，或确认后彻底删除。
 *
 * 索引以 JSON 保存在应用私有目录；文件本体尽量与回收站同卷（rename 瞬间完成），
 * 跨卷时退化为复制 + 删除。
 */
class TrashManager(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val indexFile: File get() = File(context.filesDir, "trash/index.json")

    /** 跨卷删除（外置 SD 卡、应用私有目录等）时的落脚点。 */
    private val fallbackDir: File get() = File(context.filesDir, "trash/files")

    private val externalRoot: String by lazy {
        runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrDefault("/sdcard")
    }

    /** 当前最近删除列表，按删除时间倒序；文件已丢失的记录会被清理掉。 */
    fun list(): List<TrashEntry> {
        val all = readIndex()
        val alive = all.filter { File(it.storedPath).exists() }
        if (alive.size != all.size) writeIndex(alive)
        return alive.sortedByDescending { it.deletedAt }
    }

    fun count(): Int = list().size

    /** 把文件移入最近删除，返回是否成功。 */
    fun moveToTrash(file: File): Boolean {
        if (!file.exists()) return false
        val isDirectory = file.isDirectory
        val size = if (isDirectory) -1L else file.length()
        val name = file.name
        val originalPath = file.absolutePath

        val id = Uuid.random().toString()
        var stored = File(trashDirFor(file), id)
        var moved = runCatching { file.renameTo(stored) }.getOrDefault(false)
        if (!moved) {
            // 跨卷：先复制到应用私有目录，再删原文件
            val dir = fallbackDir.apply { mkdirs() }
            stored = File(dir, id)
            moved = runCatching {
                if (isDirectory) {
                    file.copyRecursively(stored, overwrite = true)
                    file.deleteRecursively()
                } else {
                    stored.parentFile?.mkdirs()
                    file.copyTo(stored, overwrite = true)
                    file.delete()
                }
            }.getOrDefault(false)
        }
        if (!moved) {
            runCatching { stored.deleteRecursively() }
            return false
        }

        writeIndex(
            readIndex().filterNot { it.originalPath == originalPath } + TrashEntry(
                id = id,
                name = name,
                originalPath = originalPath,
                storedPath = stored.absolutePath,
                deletedAt = System.currentTimeMillis(),
                sizeBytes = size,
                isDirectory = isDirectory,
            )
        )
        return true
    }

    /** 恢复到原路径；原路径已被占用时自动改名（追加序号）。 */
    fun restore(entry: TrashEntry): Boolean {
        val stored = File(entry.storedPath)
        if (!stored.exists()) {
            removeFromIndex(setOf(entry.id))
            return false
        }
        val target = uniqueTarget(File(entry.originalPath))
        target.parentFile?.mkdirs()
        val ok = runCatching {
            when {
                stored.renameTo(target) -> true
                stored.isDirectory -> {
                    stored.copyRecursively(target, overwrite = true)
                    stored.deleteRecursively()
                }

                else -> {
                    stored.copyTo(target, overwrite = true)
                    stored.delete()
                }
            }
        }.getOrDefault(false)
        if (ok) removeFromIndex(setOf(entry.id))
        return ok
    }

    /** 恢复全部记录，返回成功条数。 */
    fun restoreAll(): Int = list().count { restore(it) }

    /** 彻底删除一条记录。 */
    fun delete(entry: TrashEntry) {
        runCatching { File(entry.storedPath).deleteRecursively() }
        removeFromIndex(setOf(entry.id))
    }

    /** 清空最近删除，彻底删除全部记录。 */
    fun deleteAll() {
        list().forEach { runCatching { File(it.storedPath).deleteRecursively() } }
        writeIndex(emptyList())
    }

    /** 回收站尽量与原文件同卷，这样 rename 才能瞬间完成。 */
    private fun trashDirFor(file: File): File {
        val abs = file.absolutePath
        val root = externalRoot
        if (abs == root || abs.startsWith("$root/")) return File(root, TRASH_DIR_NAME)
        val volume = runCatching {
            File("/storage").listFiles()?.firstOrNull {
                it.isDirectory && it.name !in setOf("emulated", "self") &&
                    abs.startsWith(it.absolutePath + "/")
            }
        }.getOrNull()
        return volume?.let { File(it, TRASH_DIR_NAME) } ?: fallbackDir
    }

    /** 原路径已被占用时，生成一个不冲突的路径：name (1).ext */
    private fun uniqueTarget(target: File): File {
        if (!target.exists()) return target
        val parent = target.parentFile ?: return target
        val base = target.name.substringBeforeLast('.', target.name)
        val ext = target.name.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidate = File(parent, if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun readIndex(): List<TrashEntry> = runCatching {
        indexFile.takeIf { it.exists() }
            ?.let { json.decodeFromString<List<TrashEntry>>(it.readText()) }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun writeIndex(entries: List<TrashEntry>) {
        runCatching {
            indexFile.parentFile?.mkdirs()
            indexFile.writeText(json.encodeToString(entries))
        }
    }

    private fun removeFromIndex(ids: Set<String>) {
        writeIndex(readIndex().filterNot { it.id in ids })
    }

    private companion object {
        const val TRASH_DIR_NAME = ".rikkahub-trash"
    }
}
