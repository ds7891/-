package me.rerere.rikkahub.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

class ChatInputState {
    val textContent = TextFieldState()
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)

    /** 引用卡片：随本次发送一起带给模型的某条历史消息内容。 */
    var quotedMessage by mutableStateOf<QuotedMessage?>(null)
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        quotedMessage = null
        editingParts = null
        editingAttachmentUrls = emptySet()
    }

    /** 把某条消息的输出放进引用卡片，发送时作为既有上下文一并带给模型。 */
    fun quoteMessage(message: UIMessage) {
        val text = message.toText().trim()
        if (text.isBlank()) return
        quotedMessage = QuotedMessage(role = message.role, text = text)
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun setContents(contents: List<UIMessagePart>) {
        val lastTextIndex = contents.indexOfLast { it is UIMessagePart.Text }
        val text = if (lastTextIndex >= 0) {
            (contents[lastTextIndex] as UIMessagePart.Text).text
        } else {
            ""
        }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = contents.filter { it !is UIMessagePart.Text }
        editingParts = contents
        editingAttachmentUrls = contents.mapNotNull { it.attachmentUrlOrNull() }.toSet()
    }

    /** 发送用内容：引用卡片作为独立的引用块放在最前面。 */
    fun getContents(): List<UIMessagePart> {
        val prefix = quotedMessage?.toPromptBlock()
        val base = buildContents()
        return if (prefix == null) base else listOf(UIMessagePart.Text(prefix)) + base
    }

    private fun buildContents(): List<UIMessagePart> {
        val text = textContent.text.toString()
        if (isEditing()) {
            val originalParts = editingParts
            if (originalParts != null) {
                val editedTextIndex = originalParts.indexOfLast { it is UIMessagePart.Text }
                val remainingAttachments = messageContent.toMutableList()
                val merged = mutableListOf<UIMessagePart>()

                originalParts.forEachIndexed { index, part ->
                    when {
                        index == editedTextIndex -> {
                            if (text.isNotBlank()) {
                                merged.add(UIMessagePart.Text(text))
                            }
                        }

                        part is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                merged.add(part)
                            }
                        }

                        else -> {
                            val currentIndex = remainingAttachments.indexOf(part)
                            if (currentIndex >= 0) {
                                merged.add(remainingAttachments.removeAt(currentIndex))
                            }
                        }
                    }
                }
                if (editedTextIndex < 0 && text.isNotBlank()) {
                    merged.add(0, UIMessagePart.Text(text))
                }
                // Newly added attachments are appended in insertion order.
                merged.addAll(remainingAttachments)
                return merged
            }
            return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
        }
        return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
    }

    fun isEmpty(): Boolean {
        return textContent.text.isBlank() && messageContent.isEmpty() && quotedMessage == null
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Image(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }

    /**
     * 仅删除当前输入组件临时新增的本地文件。
     * 编辑历史消息时，原有附件不在这里删除，由会话层统一做差异清理。
     */
    fun shouldDeleteFileOnRemove(part: UIMessagePart): Boolean {
        val url = part.attachmentUrlOrNull() ?: return false
        if (!url.startsWith("file:")) return false
        return !isEditing() || url !in editingAttachmentUrls
    }

    private fun UIMessagePart.attachmentUrlOrNull(): String? {
        return when (this) {
            is UIMessagePart.Image -> this.url
            is UIMessagePart.Video -> this.url
            is UIMessagePart.Audio -> this.url
            is UIMessagePart.Document -> this.url
            else -> null
        }
    }
}

/**
 * 引用卡片：把某条历史消息的输出原样带进下一次发送，长对话里用来防失忆。
 */
data class QuotedMessage(
    val role: MessageRole,
    val text: String,
)

/** 包成一段引用块，明确标注这是既有上下文，而不是用户新提的问题。 */
internal fun QuotedMessage.toPromptBlock(): String = buildString {
    val who = if (role == MessageRole.ASSISTANT) "AI 之前的回复" else "我之前的输入"
    append("<引用片段 who=\"$who\">\n")
    append(text.trim())
    append("\n</引用片段>\n\n")
}

