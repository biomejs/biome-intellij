package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.extensions.runBiomeCLI
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.util.NodePackage
import com.intellij.openapi.project.Project
import java.nio.file.Paths

class BiomePackage(private val project: Project) {
    private val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
    private val nodePackage: NodePackage?
        get() {
            return NodePackage.findDefaultPackage(project, "@biomejs/biome", interpreter)
        }

    val configPath: String?
        get() {
            val settings = BiomeSettings.getInstance(project)
            val configurationMode = settings.configurationMode

            return when (configurationMode) {
                ConfigurationMode.DISABLED -> null
                ConfigurationMode.AUTOMATIC -> null
                ConfigurationMode.MANUAL -> BiomeSettings.getInstance(project).configPath
            }
        }

    fun versionNumber(): String? {
        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode
        return when (configurationMode) {
            ConfigurationMode.DISABLED -> null
            ConfigurationMode.AUTOMATIC -> nodePackage?.getVersion(project)?.toString()
            ConfigurationMode.MANUAL -> getBinaryVersion(binaryPath())
        }
    }

    fun binaryPath(): String? {
        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode
        return when (configurationMode) {
            ConfigurationMode.DISABLED -> null
            ConfigurationMode.AUTOMATIC -> nodePackage?.getAbsolutePackagePathToRequire(project)?.let {
                Paths.get(
                    it,
                    "bin/biome"
                )
            }?.toString()

            ConfigurationMode.MANUAL -> settings.executablePath
        }
    }


    private fun getBinaryVersion(binaryPath: String?): String? {
        if (binaryPath.isNullOrEmpty()) {
            return null
        }

        val versionRegex = Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{1,3}")
        val commandLine = GeneralCommandLine().runBiomeCLI(project, binaryPath).apply {
            addParameter("--version")
        }

        return runCatching {
            val output = ExecUtil.execAndGetOutput(commandLine)
            val matchResult = versionRegex.find(output.stdout)
            return matchResult?.value
        }.getOrNull()
    }

    companion object {
        const val configName = "biome"
        val configValidExtensions = listOf("json", "jsonc")
    }
}
