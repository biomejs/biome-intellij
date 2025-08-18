package com.github.biomejs.intellijbiome

import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.decodeFromStream

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
               return json.decodeFromStream(file.inputStream)
            } catch (_: Throwable) {
                null
            }
        }
    }

    object ExtendsSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
        override fun transformDeserialize(element: JsonElement): JsonElement =
            element as? JsonArray ?: JsonArray(listOf(element))
    }
}
