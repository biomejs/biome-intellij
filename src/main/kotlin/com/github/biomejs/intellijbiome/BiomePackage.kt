package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.util.NodePackage
import com.intellij.javascript.nodejs.util.NodePackageDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.OSAgnosticPathUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Paths

class BiomePackage(private val project: Project) {
    private val packageName = "@biomejs/biome"
    private val packageDescription = NodePackageDescriptor(packageName)

    private fun getPackage(virtualFile: VirtualFile): NodePackage? {
        val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
        return packageDescription.listAvailable(project, interpreter, virtualFile, false, true).firstOrNull()
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

    fun binaryPath(virtualFile: VirtualFile): String? {
        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode
        return when (configurationMode) {
            ConfigurationMode.DISABLED -> null
            ConfigurationMode.AUTOMATIC -> findBiomeExecutable(virtualFile)
            ConfigurationMode.MANUAL -> resolveManualExecutable(settings.executablePath)
        }
    }

    private fun resolveManualExecutable(executable: String): String {
        val base = project.basePath ?: return executable
        val path = File(executable)
        if (path.parent == null || OSAgnosticPathUtil.isAbsolute(executable)) return executable
        return File(base).resolve(path).path
    }

    private fun findBiomeExecutable(virtualFile: VirtualFile): String? {
        val path = getPackage(virtualFile)?.getAbsolutePackagePathToRequire(project)
        if (path != null) {
            return Paths.get(path, "bin/biome").toString()
        }

        return null
    }

    companion object {
        const val configName = "biome"
    }
}
