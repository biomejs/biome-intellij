package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.extensions.runProcessFuture
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.util.NodePackage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.future.await
import java.nio.file.Paths


private val versionRegex: Regex = Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{1,3}")

class BiomePackage(private val project: Project) {
    private val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
    private val nodePackage: NodePackage?
        get() {
            return NodePackage.findDefaultPackage(project, "@biomejs/biome", interpreter)
        }

    fun configPath(file: VirtualFile): String? {
        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode
        return when (configurationMode) {
            ConfigurationMode.DISABLED -> null
            ConfigurationMode.AUTOMATIC -> findPathUpwards(file, configValidExtensions.map { "$configName.$it" })?.path
            ConfigurationMode.MANUAL -> settings.configPath
        }
    }

    suspend fun versionNumber(): String? {
        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode
        return when (configurationMode) {
            ConfigurationMode.DISABLED -> null
            ConfigurationMode.AUTOMATIC -> nodePackage?.getVersion(project)?.toString()
            ConfigurationMode.MANUAL -> getBinaryVersion(binaryPath(null, true))
        }
    }

    fun binaryPath(configPath: String?,
        showVersion: Boolean): String? {
        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode
        return when (configurationMode) {
            ConfigurationMode.DISABLED -> null // don't try to find the executable path if the configuration file does not exist.
            // This will prevent start LSP and formatting in case if biome is not used in the project.
            ConfigurationMode.AUTOMATIC -> if (configPath != null || showVersion) findBiomeExecutable() else null // if configuration mode is manual, return the executable path if it is not empty string.
            // Otherwise, try to find the executable path.
            ConfigurationMode.MANUAL -> if (settings.executablePath == "") findBiomeExecutable() else settings.executablePath
        }
    }

    private fun findBiomeExecutable(): String? {
        val path = nodePackage?.getAbsolutePackagePathToRequire(project)
        if (path != null) {
            return Paths.get(path, "bin/biome").toString()
        }

        return null
    }


    private suspend fun getBinaryVersion(binaryPath: String?): String? {
        if (binaryPath.isNullOrEmpty()) {
            return null
        }

        val processHandler =
            BiomeTargetRunBuilder(project).getBuilder(binaryPath).addParameters(listOf("--version")).build()
        return runCatching {
            val result = processHandler.runProcessFuture().await()
            val processOutput = result.processOutput
            val stdout = processOutput.stdout.trim()
            val matchResult = versionRegex.find(stdout)
            return matchResult?.value
        }.getOrNull()
    }

    companion object {
        const val configName = "biome"
        val configValidExtensions = listOf("json", "jsonc")
    }

    private fun findPathUpwards(file: VirtualFile,
        fileName: List<String>): VirtualFile? {
        var cur = file.parent
        while (cur != null) {
            if (cur.children.find { name -> fileName.any { it == name.name } } != null) {
                return cur
            }
            cur = cur.parent
        }
        return null
    }

    fun compareVersion(version1: String,
        version2: String): Int {
        val parts1 = version1.split(".").map { it.toInt() }
        val parts2 = version2.split(".").map { it.toInt() }

        val maxLength = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLength) {
            val v1 = if (i < parts1.size) parts1[i] else 0
            val v2 = if (i < parts2.size) parts2[i] else 0

            when {
                v1 < v2 -> return -1
                v1 > v2 -> return 1
            }
        }

        return 0
    }

}
