package me.rerere.rikkahub.data.ai.tools.local

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 极简的 Android 二进制 XML（AXML，即 APK 内 AndroidManifest.xml 的格式）解析器。
 *
 * 只依赖 JDK，用于在不引入额外库的前提下读取 APK 的清单信息，
 * 例如包名、版本号、SDK 版本、权限与四大组件。
 */
internal object AndroidBinaryXml {

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val RES_XML_RESOURCE_MAP = 0x0180
    private const val RES_XML_START_ELEMENT = 0x0102

    private const val TYPE_NULL = 0x00
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_FLOAT = 0x04
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    /** 判断一段字节是否为 AXML（二进制 XML）。 */
    fun isBinaryXml(data: ByteArray): Boolean =
        data.size >= 8 &&
            data[0] == 0x03.toByte() && data[1] == 0x00.toByte() &&
            data[2] == 0x08.toByte() && data[3] == 0x00.toByte()

    /** 解析二进制 XML，返回根元素；解析失败返回 null。 */
    fun parse(data: ByteArray): AxmlElement? {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if ((buffer.getShort(0).toInt() and 0xFFFF) != RES_XML) return null
        var offset = 8
        var strings: List<String> = emptyList()
        var resourceMap: IntArray = IntArray(0)
        val stack = ArrayDeque<AxmlElement>()
        var root: AxmlElement? = null
        while (offset + 8 <= data.size) {
            val type = buffer.getShort(offset).toInt() and 0xFFFF
            val headerSize = buffer.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buffer.getInt(offset + 4)
            if (chunkSize <= 0 || offset + chunkSize > data.size) break
            when (type) {
                RES_STRING_POOL -> strings = readStringPool(buffer, offset, chunkSize)
                RES_XML_RESOURCE_MAP -> {
                    val count = (chunkSize - headerSize) / 4
                    resourceMap = IntArray(count) { buffer.getInt(offset + headerSize + it * 4) }
                }
                RES_XML_START_ELEMENT -> {
                    val name = strings.getOrElse(buffer.getInt(offset + 20)) { "" }
                    val namespace = strings.getOrElse(buffer.getInt(offset + 16)) { "" }
                    val attributeStart = buffer.getShort(offset + 24).toInt() and 0xFFFF
                    val attributeSize = buffer.getShort(offset + 26).toInt() and 0xFFFF
                    val attributeCount = buffer.getShort(offset + 28).toInt() and 0xFFFF
                    val attributes = ArrayList<AxmlAttribute>(attributeCount)
                    for (i in 0 until attributeCount) {
                        val base = offset + 16 + attributeStart + i * attributeSize
                        if (base + 20 > data.size) break
                        val attrNs = strings.getOrElse(buffer.getInt(base)) { "" }
                        val nameIndex = buffer.getInt(base + 4)
                        val rawIndex = buffer.getInt(base + 8)
                        val valueType = buffer.get(base + 15).toInt() and 0xFF
                        val valueData = buffer.getInt(base + 16)
                        val attrName = resolveAttrName(strings, resourceMap, nameIndex)
                        attributes += AxmlAttribute(
                            namespace = attrNs.ifBlank { null },
                            name = attrName,
                            value = resolveValue(strings, valueType, valueData, rawIndex),
                            intValue = if (valueType == TYPE_INT_DEC || valueType == TYPE_INT_HEX ||
                                valueType == TYPE_INT_BOOLEAN || valueType == TYPE_REFERENCE
                            ) valueData else null,
                        )
                    }
                    val element = AxmlElement(namespace.ifBlank { null }, name, attributes)
                    stack.lastOrNull()?.children?.add(element)
                    if (root == null) root = element
                    stack.addLast(element)
                }
            }
            offset += chunkSize
        }
        return root
    }

    private fun readStringPool(buffer: ByteBuffer, offset: Int, chunkSize: Int): List<String> {
        val stringCount = buffer.getInt(offset + 8)
        val flags = buffer.getInt(offset + 16)
        val stringsStart = buffer.getInt(offset + 20)
        val isUtf8 = (flags and (1 shl 8)) != 0
        val base = offset + stringsStart
        val result = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val stringOffset = base + buffer.getInt(offset + 28 + i * 4)
            result += if (isUtf8) readUtf8String(buffer, stringOffset) else readUtf16String(buffer, stringOffset)
        }
        return result
    }

    private fun readUtf8String(buffer: ByteBuffer, offset: Int): String {
        var position = offset
        var length = buffer.get(position).toInt() and 0xFF
        if ((length and 0x80) != 0) {
            length = ((length and 0x7F) shl 8) or (buffer.get(position + 1).toInt() and 0xFF)
            position += 2
        } else {
            position += 1
        }
        // 第二个长度是 UTF-16 字符数，这里读取后跳过
        var charLength = buffer.get(position).toInt() and 0xFF
        if ((charLength and 0x80) != 0) {
            charLength = ((charLength and 0x7F) shl 8) or (buffer.get(position + 1).toInt() and 0xFF)
            position += 2
        } else {
            position += 1
        }
        val bytes = ByteArray(length)
        for (i in 0 until length) bytes[i] = buffer.get(position + i)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16String(buffer: ByteBuffer, offset: Int): String {
        var position = offset
        var length = buffer.getShort(position).toInt() and 0xFFFF
        if ((length and 0x8000) != 0) {
            length = ((length and 0x7FFF) shl 16) or (buffer.getShort(position + 2).toInt() and 0xFFFF)
            position += 4
        } else {
            position += 2
        }
        val chars = StringBuilder(length)
        for (i in 0 until length) {
            chars.append(buffer.getShort(position + i * 2).toInt().toChar())
        }
        return chars.toString()
    }

    private fun resolveAttrName(strings: List<String>, resourceMap: IntArray, index: Int): String {
        val fromPool = strings.getOrElse(index) { "" }
        if (fromPool.isNotBlank()) return fromPool
        val resourceId = resourceMap.getOrElse(index) { 0 }
        return KNOWN_ATTR_NAMES[resourceId] ?: "attr_0x%08x".format(resourceId)
    }

    private fun resolveValue(strings: List<String>, type: Int, data: Int, rawIndex: Int): String? = when (type) {
        TYPE_STRING -> strings.getOrNull(data) ?: strings.getOrNull(rawIndex)
        TYPE_INT_DEC -> data.toString()
        TYPE_INT_HEX -> "0x%08x".format(data)
        TYPE_INT_BOOLEAN -> (data != 0).toString()
        TYPE_REFERENCE -> "@0x%08x".format(data)
        TYPE_FLOAT -> Float.fromBits(data).toString()
        TYPE_NULL -> null
        else -> strings.getOrNull(rawIndex)
    }

    /** 常见框架属性名，用于少数未把属性名写入字符串池的 APK 兜底。 */
    private val KNOWN_ATTR_NAMES: Map<Int, String> = mapOf(
        0x01010001 to "label",
        0x01010002 to "icon",
        0x01010003 to "name",
        0x0101000f to "debuggable",
        0x0101020c to "minSdkVersion",
        0x0101021b to "versionCode",
        0x0101021c to "versionName",
        0x01010270 to "targetSdkVersion",
        0x01010280 to "allowBackup",
        0x010102b7 to "largeHeap",
        0x010102d3 to "roundIcon",
        0x01010010 to "theme",
    )
}

internal data class AxmlAttribute(
    val namespace: String?,
    val name: String,
    val value: String?,
    val intValue: Int?,
)

internal data class AxmlElement(
    val namespace: String?,
    val name: String,
    val attributes: List<AxmlAttribute>,
) {
    val children: MutableList<AxmlElement> = mutableListOf()

    fun attr(name: String): AxmlAttribute? = attributes.firstOrNull { it.name == name }

    fun attrString(name: String): String? = attr(name)?.value

    fun attrInt(name: String): Int? = attr(name)?.intValue

    fun attrBool(name: String): Boolean? = attr(name)?.value?.toBooleanStrictOrNull()

    /** 递归查找指定名称的子元素。 */
    fun findAll(tag: String): List<AxmlElement> {
        val result = mutableListOf<AxmlElement>()
        if (name == tag) result += this
        children.forEach { result += it.findAll(tag) }
        return result
    }

    /** 反序列化为可读的 XML 文本。 */
    fun toXmlString(indent: Int = 0): String {
        val pad = "    ".repeat(indent)
        val sb = StringBuilder()
        sb.append(pad).append('<').append(name)
        attributes.forEach { attribute ->
            val prefix = if (attribute.namespace == "http://schemas.android.com/apk/res/android") "android:" else ""
            sb.append(' ').append(prefix).append(attribute.name)
                .append("=\"").append(attribute.value.orEmpty().replace("\"", "&quot;")).append('"')
        }
        if (children.isEmpty()) {
            sb.append("/>\n")
        } else {
            sb.append(">\n")
            children.forEach { sb.append(it.toXmlString(indent + 1)) }
            sb.append(pad).append("</").append(name).append(">\n")
        }
        return sb.toString()
    }
}