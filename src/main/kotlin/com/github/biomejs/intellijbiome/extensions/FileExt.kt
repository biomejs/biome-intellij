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
    while (cur != null) {
        cur.children.find(predicate)?.let { return it }

        if (root != null && cur == root) {
            break
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
  this.findNearestFile(root) { file ->
    // 1. Ignore files that are not Biome config files
    if (!file.isBiomeConfigFile()) return@findNearestFile false

    // 2. If called with a 'root' (submodule), accept the first one found
    if (root != null) return@findNearestFile true

    // 3. If not called with a 'root' (full monorepo), continue requiring it to be a root config
    BiomeConfig.loadFromFile(file)?.isRootConfig() ?: false
  }
