package com.github.biomejs.intellijbiome.extensions

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

fun File.isNodeScript(): Boolean {
    val reader = BufferedReader(FileReader(this))
    val line = reader.readLine()
    return line.startsWith("#!/usr/bin/env node")
}

/**
 * Find the nearest file that satisfies the predicate.
 * If `root` is not null, stops finding at the specified directory.
 */
private fun VirtualFile.findNearestFile(
    predicate: (file: VirtualFile) -> Boolean,
    root: VirtualFile? = null,
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
    this.findNearestFile({ f -> f.isBiomeConfigFile() }, root)
