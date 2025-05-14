package com.github.biomejs.intellijbiome.extensions

import com.github.biomejs.intellijbiome.BiomeConfig
import com.intellij.openapi.vfs.VirtualFile

fun VirtualFile.isBiomeConfigFile(): Boolean =
    BiomeConfig.isBiomeConfigFile(this)
