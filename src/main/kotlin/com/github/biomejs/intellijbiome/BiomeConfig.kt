package com.github.biomejs.intellijbiome

import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer
import java.io.IOException

@Serializable
data class BiomeConfig(
    val root: Boolean? = null,

    @Serializable(with = ExtendsSerializer::class)
    val extends: List<String>? = null,
) {
    fun isRootConfig() =
        root != false && extends?.contains("//") != true

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun loadFromFile(file: VirtualFile): BiomeConfig? {
            val json = Json {
                allowComments = true
                allowTrailingComma = true
                ignoreUnknownKeys = true
            }

            return try {
                file.inputStream.use { stream ->
                    // Handle expected read failures before close so a close-time
                    // cancellation or fatal failure can still propagate.
                    try {
                        json.decodeFromString<BiomeConfig>(stream.reader(Charsets.UTF_8).readText())
                    } catch (_: SerializationException) {
                        null
                    } catch (_: IOException) {
                        null
                    }
                }
            } catch (_: IOException) {
                null
            }
        }
    }

    object ExtendsSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
        override fun transformDeserialize(element: JsonElement): JsonElement =
            element as? JsonArray ?: JsonArray(listOf(element))
    }
}
