package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreter
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.util.NodePackage
import com.intellij.javascript.nodejs.util.NodePackageDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Paths

class BiomePackage(private val project: Project) {
    private val packageName = "@biomejs/biome"
    private val packageDescription = NodePackageDescriptor(packageName)

    fun getPackage(virtualFile: VirtualFile?): NodePackage? =
        getPackage(virtualFile) { NodeJsInterpreterManager.getInstance(project).interpreter }

    internal fun getPackage(virtualFile: VirtualFile?, interpreter: NodeJsInterpreter?): NodePackage? =
        getPackage(virtualFile) { interpreter }

    private fun getPackage(virtualFile: VirtualFile?, interpreter: () -> NodeJsInterpreter?): NodePackage? {
        if (virtualFile != null) {
            val available = packageDescription.listAvailable(
                project,
                interpreter(),
                virtualFile,
                false,
                true
            )
            if (available.isNotEmpty()) {
                return available[0]
            }
        }

        var pkg = packageDescription.findUnambiguousDependencyPackage(project) ?: NodePackage.findDefaultPackage(
            project,
            packageName,
            interpreter()
        )

        return pkg
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
            ConfigurationMode.MANUAL -> settings.executablePath.takeUnless { it.isBlank() }
                ?: findBiomeExecutable(virtualFile)
        }
    }

    private fun findBiomeExecutable(virtualFile: VirtualFile?): String? {
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
