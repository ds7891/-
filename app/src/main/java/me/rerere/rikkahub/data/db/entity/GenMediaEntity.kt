package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class GenMediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("path")
    val path: String,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("prompt")
    val prompt: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo(name = "type", defaultValue = TYPE_IMAGE_GENERATION)
    val type: String = TYPE_IMAGE_GENERATION,
    @ColumnInfo("source_paths")
    val sourcePaths: String? = null,
) {
    companion object {
        const val TYPE_IMAGE_GENERATION = "image_generation"
        const val TYPE_IMAGE_EDIT = "image_edit"
        const val TYPE_VIDEO_GENERATION = "video_generation"

        /** 图片类记录（用于图像生成相册过滤）。 */
        val IMAGE_TYPES = listOf(TYPE_IMAGE_GENERATION, TYPE_IMAGE_EDIT)
    }
}
