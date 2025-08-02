package com.github.biomejs.intellijbiome.extensions

import com.github.biomejs.intellijbiome.BiomeConfig
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * Find the nearest file that satisfies the predicate.
 * If `root` is not null, stops finding at the specified directory.
 */
private fun VirtualFile.findNearestFile(
    root: VirtualFile? = null,
    predicate: (file: VirtualFile) -> Boolean,
): VirtualFile? {
    var cur = this.parent
    while (cur != null && VfsUtil.isUnder(cur, mutableSetOf(root))) {
        val f = cur.children.find(predicate)
        if (f != null) {
            return f
        }
        cur = cur.parent
    }
    return null
}

private const val configName = "biome"
private val configValidExtensions = listOf("json", "jsonc")

fun VirtualFile.isBiomeConfigFile(): Boolean =
    configValidExtensions.map { "${configName}.$it" }.contains(this.name)

fun VirtualFile.findNearestBiomeConfig(root: VirtualFile? = null, onlyRootConfig: Boolean = true): VirtualFile? =
    this.findNearestFile(root) { file ->
        if (file.isBiomeConfigFile()) {
            // Skip biome.json(c) files that includes `root: false` or `extends: //`
            BiomeConfig.loadFromFile(file)?.let { !onlyRootConfig || it.isRootConfig() } ?: false
        } else {
            false
        }
    } ?: if (onlyRootConfig) {
        // If no root config was found, fallback to any nearest nested (child) config.
        this.findNearestBiomeConfig(root, false)
    } else {
        null
    }
