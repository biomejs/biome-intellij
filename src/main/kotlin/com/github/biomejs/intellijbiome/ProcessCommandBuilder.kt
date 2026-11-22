package com.github.biomejs.intellijbiome

import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.Charset
import java.nio.file.Path

internal const val BIOME_CONFIG_PATH = "BIOME_CONFIG_PATH"

interface ProcessCommandBuilder {
    fun setWorkingDirectory(path: String?): ProcessCommandBuilder
    fun setInputFile(file: VirtualFile?): ProcessCommandBuilder
    fun addParameters(params: List<ProcessCommandParameter>): ProcessCommandBuilder
    fun setExecutable(executable: String): ProcessCommandBuilder
    fun setCharset(charset: Charset): ProcessCommandBuilder
    fun build(v1ConfigDirectory: Path? = null): BiomeTargetRun
}
