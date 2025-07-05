package com.github.biomejs.intellijbiome

import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

@Serializable
data class BiomeConfig(
    val root: Boolean? = null,
    val extends: String? = null,
) {
    fun isRootConfig() = root != false && extends != "//"

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun loadFromFile(file: VirtualFile): BiomeConfig? {
            val json = Json { ignoreUnknownKeys = true }
//            return try {
               return json.decodeFromStream(file.inputStream)
//            } catch (_: Throwable) {
//                null
//            }
        }
    }
}
