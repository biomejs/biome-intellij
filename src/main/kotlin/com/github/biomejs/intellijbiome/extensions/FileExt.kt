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

fun VirtualFile.findNearestBiomeConfig(root: VirtualFile? = null): VirtualFile? =
    this.findNearestFile(root) {
        if (it.isBiomeConfigFile()) {
            // Skip biome.json(c) files that includes `root: false` or `extends: //`
            BiomeConfig.loadFromFile(it)?.isRootConfig() ?: false
        } else {
            false
        }
    }
