package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.extensions.runWithNodeInterpreter
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.javascript.nodejs.library.node_modules.NodeModulesDirectoryManager
import com.intellij.openapi.project.Project

object BiomePackage {
    fun versionNumber(project: Project, binaryPath: String?): String? {
        if (binaryPath.isNullOrEmpty()) {
            return null
        }

        val versionRegex = Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{1,3}")
        val commandLine = GeneralCommandLine().runWithNodeInterpreter(project, binaryPath).apply {
            addParameter("--version")
        }

        val output = ExecUtil.execAndGetOutput(commandLine)
        val matchResult = versionRegex.find(output.stdout)
        return matchResult?.value
    }

    fun binaryPath(project: Project): String? {
        val directoryManager = NodeModulesDirectoryManager.getInstance(project)
        val executablePath = BiomeSettings.getInstance(project).executablePath

        if (executablePath.isNotEmpty()) {
            return executablePath
        }

        val binaryFile = directoryManager.nodeModulesDirs
            .asSequence()
            .mapNotNull { it.findFileByRelativePath("@biomejs/biome/bin/biome") }
            .filter { it.isValid }
            .firstOrNull()

        return binaryFile?.path
    }

    fun configPath(project: Project): String? {
        val configPath = BiomeSettings.getInstance(project).configPath

        if (configPath.isNotEmpty()) {
            return configPath
        }

        return null
    }
}
