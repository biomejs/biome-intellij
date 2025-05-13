package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.extensions.runProcessFuture
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.util.NodePackage
import com.intellij.javascript.nodejs.util.NodePackageDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.future.await
import java.nio.file.Paths


private val versionRegex: Regex = Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{1,3}")

class BiomePackage(private val project: Project) {
    private val packageName = "@biomejs/biome"
    private val packageDescription = NodePackageDescriptor(packageName)

    fun getPackage(contextFileOrDirectory: VirtualFile?): NodePackage? {
        val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
        val listAvailable = packageDescription.listAvailable(project, interpreter, contextFileOrDirectory, false, true)
        return listAvailable.firstOrNull()
            ?: packageDescription.findUnambiguousDependencyPackage(project)
            ?: NodePackage.findDefaultPackage(project, packageName, interpreter)
    }

    fun configPath(): String? {
        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode
        return when (configurationMode) {
            ConfigurationMode.DISABLED -> null
            ConfigurationMode.AUTOMATIC -> null // Let Biome find the config file
            ConfigurationMode.MANUAL -> settings.configPath
        }
    }

    suspend fun versionNumber(): String? {
        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode
        return when (configurationMode) {
            ConfigurationMode.DISABLED -> null
            ConfigurationMode.AUTOMATIC -> getPackage(null)?.version?.toString()
            ConfigurationMode.MANUAL -> getBinaryVersion(settings.executablePath)
        }
    }

    fun binaryPath(virtualFile: VirtualFile?): String? {
        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode
        return when (configurationMode) {
            ConfigurationMode.DISABLED -> null // don't try to find the executable path if the configuration file does not exist.
            // This will prevent start LSP and formatting in case if biome is not used in the project.
            ConfigurationMode.AUTOMATIC -> findBiomeExecutable(virtualFile) // if configuration mode is manual, return the executable path if it is not empty string.
            // Otherwise, try to find the executable path.
            ConfigurationMode.MANUAL -> settings.executablePath
        }
    }

    private fun findBiomeExecutable(contextFileOrDirectory: VirtualFile?): String? {
        val path = getPackage(contextFileOrDirectory)?.getAbsolutePackagePathToRequire(project)
        if (path != null) {
            return Paths.get(path, "bin/biome").toString()
        }

        return null
    }


    private suspend fun getBinaryVersion(binaryPath: String?): String? {
        if (binaryPath.isNullOrEmpty()) {
            return null
        }

        val processHandler = BiomeTargetRunBuilder(project).getBuilder(binaryPath)
            .addParameters(listOf(ProcessCommandParameter.Value("--version"))).build().startProcess()

        return runCatching {
            val result = runProcessFuture(processHandler).await()
            val processOutput = result.processOutput
            val stdout = processOutput.stdout
            val matchResult = versionRegex.find(stdout)
            return matchResult?.value
        }.getOrNull()
    }

    companion object {
        const val configName = "biome"
    }
}
