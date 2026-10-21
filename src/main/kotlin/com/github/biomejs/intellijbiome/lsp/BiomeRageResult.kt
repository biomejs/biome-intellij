package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomeBundle
import com.google.gson.JsonElement

/** Entries are an externally tagged union; validate their JSON types before rendering. */
data class BiomeRageResult(val entries: JsonElement? = null) {
    internal fun toPlainText(): String = BiomeTroubleshootingText().also { appendTo(it) }.toString()

    internal fun appendTo(text: BiomeTroubleshootingText) {
        val values = entries?.takeIf { it.isJsonArray }?.asJsonArray
        if (values == null) {
            text.line(BiomeBundle.message("biome.troubleshooting.response.unavailable"))
            return
        }
        if (values.isEmpty) text.line(BiomeBundle.message("biome.troubleshooting.response.empty"))
        var nodes = 0
        fun markup(value: JsonElement?): List<String>? {
            val array = value?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            if (array.size() > 4096 - nodes) {
                text.truncate()
                return null
            }
            nodes += array.size()
            return array.map { node ->
                if (!node.isJsonObject) return null
                node.asJsonObject.get("content").stringValue() ?: return null
            }
        }
        for ((index, entry) in values.withIndex()) {
            if (text.isTruncated) break
            if (index == 256) {
                text.truncate()
                break
            }
            val fields = entry.takeIf { it.isJsonObject }?.asJsonObject
            val valid = if (fields?.size() != 1) false else when {
                fields.has("section") -> fields.get("section").stringValue()?.let { text.line(it); true } ?: false
                fields.has("pair") -> {
                    val pair = fields.get("pair").takeIf { it.isJsonObject }?.asJsonObject
                    val name = pair?.get("name").stringValue()
                    val content = markup(pair?.get("value"))
                    if (name == null || content == null) false else {
                        text.append(name).append(": ")
                        content.forEach { text.append(it) }
                        text.line("")
                        true
                    }
                }
                fields.has("markup") -> markup(fields.get("markup"))?.let { content ->
                    content.forEach { text.append(it) }
                    text.line("")
                    true
                } ?: false
                else -> false
            }
            if (!valid) text.line(BiomeBundle.message("biome.troubleshooting.entry.omitted"))
        }
    }
}

private fun JsonElement?.stringValue(): String? =
    this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

/** Shared budget for local metadata and server text; never interpret markup as HTML. */
internal class BiomeTroubleshootingText {
    private val content = StringBuilder()
    private val marker = "\n" + BiomeBundle.message("biome.troubleshooting.truncated") + "\n"
    var isTruncated = false
        private set

    fun truncate() { isTruncated = true }

    fun append(value: CharSequence): BiomeTroubleshootingText {
        if (isTruncated) return this
        var index = 0
        while (index < value.length) {
            if (content.length >= 65_536 - marker.length) {
                truncate()
                break
            }
            val char = value[index++]
            when {
                char == '\r' -> {
                    content.append('\n')
                    if (index < value.length && value[index] == '\n') index++
                }
                char == '\n' || char == '\t' || !char.isISOControl() -> content.append(char)
            }
        }
        return this
    }

    fun line(value: CharSequence) { append(value).append("\n") }
    fun pair(name: String, value: String) { append(name).append(": ").append(value).append("\n") }
    override fun toString(): String = content.toString() + if (isTruncated) marker else ""
}
