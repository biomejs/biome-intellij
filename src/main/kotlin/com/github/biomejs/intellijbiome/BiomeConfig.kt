package com.github.biomejs.intellijbiome

import com.intellij.openapi.vfs.VirtualFile

object BiomeConfig {
    const val baseName = "biome"
    val validExtensions = listOf("json", "jsonc")

    val validFileNames: Set<String> = validExtensions.map { "$baseName.$it" }.toSet()

    fun isBiomeConfigFile(file: VirtualFile?): Boolean {
        return file != null && validFileNames.contains(file.name)
    }
}
