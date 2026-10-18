package com.github.biomejs.intellijbiome.configuration

import com.github.biomejs.intellijbiome.nodeLauncherPath
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.github.biomejs.intellijbiome.wslDistributionId
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreter
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal data class BiomeConfigurationSettings(
    val mode: ConfigurationMode,
    val configPath: String = "",
    val executablePath: String = "",
    val interpreter: NodeJsInterpreter? = null,
) {
    companion object {
        fun capture(project: Project): BiomeConfigurationSettings = BiomeSettings.getInstance(project).let {
            BiomeConfigurationSettings(it.configurationMode, it.configPath, it.executablePath,
                NodeJsInterpreterManager.getInstance(project).interpreter)
        }
    }
}

internal sealed interface BiomeConfigurationResolution {
    data class Found(val file: VirtualFile, val base: VirtualFile) : BiomeConfigurationResolution
    data class Unavailable(val reason: Reason) : BiomeConfigurationResolution
    data class Ambiguous(val candidates: List<Found>) : BiomeConfigurationResolution

    enum class Reason { DISABLED, NO_PROJECT_BASE, INVALID_SELECTION, MISSING_CONFIGURATION, UNRESOLVED_TARGET }
}

internal object BiomeConfigurationResolver {
    /** Resolves the editable source configuration, independently of language-server workspace ownership. */
    fun resolve(
        project: Project,
        selectedFile: VirtualFile?,
        settings: BiomeConfigurationSettings,
    ): BiomeConfigurationResolution {
        if (settings.mode == ConfigurationMode.DISABLED) return unavailable(BiomeConfigurationResolution.Reason.DISABLED)
        if (project.isDisposed || project.isDefault) return unavailable(BiomeConfigurationResolution.Reason.NO_PROJECT_BASE)
        if (selectedFile != null && !selectedFile.isValid) return unavailable(BiomeConfigurationResolution.Reason.INVALID_SELECTION)
        val bases = project.getBaseDirectories().filter { it.isValid && it.isDirectory }
        val owningBase = selectedFile?.let { file ->
            bases.filter { VfsUtilCore.isAncestor(it, file, false) }.maxByOrNull { it.path.length }
        }
        val manualPath = settings.configPath.takeIf { settings.mode == ConfigurationMode.MANUAL && it.isNotBlank() }
        if (selectedFile != null && owningBase == null && manualPath == null) {
            return unavailable(BiomeConfigurationResolution.Reason.NO_PROJECT_BASE)
        }
        val candidates = owningBase?.let { listOf(it) } ?: bases
        if (candidates.isEmpty()) return unavailable(BiomeConfigurationResolution.Reason.NO_PROJECT_BASE)
        val found = mutableListOf<BiomeConfigurationResolution.Found>()
        var unresolvedTarget = false
        for (base in candidates) {
            ProgressManager.checkCanceled()
            val config = if (manualPath == null) nearestConfiguration(selectedFile ?: base, base) else {
                val path = try {
                    manualHostPath(base, settings, manualPath)
                } catch (_: InvalidPathException) {
                    // Invalid paths have the same unavailable outcome as a missing override.
                    continue
                }
                if (path == null) unresolvedTarget = true
                path?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.let { file ->
                    if (file.isDirectory) standardConfiguration(file) else file.takeIf {
                        it.isValid && it.extension in listOf("json", "jsonc")
                    }
                }
            }
            if (config != null) found += BiomeConfigurationResolution.Found(config, base)
        }
        if (unresolvedTarget) return unavailable(BiomeConfigurationResolution.Reason.UNRESOLVED_TARGET)
        return when (found.size) {
            0 -> unavailable(BiomeConfigurationResolution.Reason.MISSING_CONFIGURATION)
            1 -> found.single()
            else -> BiomeConfigurationResolution.Ambiguous(found.toList())
        }
    }

    private fun nearestConfiguration(file: VirtualFile, base: VirtualFile): VirtualFile? {
        var directory = if (file.isDirectory) file else file.parent
        while (directory != null && VfsUtilCore.isAncestor(base, directory, false)) {
            ProgressManager.checkCanceled()
            standardConfiguration(directory)?.let { return it }
            if (directory == base) break
            directory = directory.parent
        }
        return null
    }

    private fun standardConfiguration(directory: VirtualFile): VirtualFile? =
        sequenceOf("biome.json", "biome.jsonc").mapNotNull { directory.findChild(it) }
            .firstOrNull { it.isValid && !it.isDirectory }

    /** Uses the launcher's target policy, without constructing a command or asking WSL to map a path. */
    private fun manualHostPath(
        base: VirtualFile,
        settings: BiomeConfigurationSettings,
        configuredPath: String,
    ): String? {
        val path = Path.of(configuredPath)
        val normalized = configuredPath.replace('\\', '/')
        val relative = !normalized.startsWith('/') && !path.isAbsolute && path.root == null
        val baseDistribution = wslDistributionId(base.path)
        if (relative && baseDistribution == null) {
            // Relative overrides belong to each project base, never the IDE's working directory.
            return base.toNioPath().resolve(path).normalize().toString().replace('\\', '/')
        }
        val interpreter = settings.interpreter
        val explicitExecutable = settings.executablePath.takeIf { it.isNotBlank() }
        val distribution = if (explicitExecutable != null) {
            val launcher = nodeLauncherPath(explicitExecutable, base.path, interpreter) ?: explicitExecutable
            wslDistributionId(launcher)
        } else (interpreter as? WslNodeInterpreter)?.wslDistributionId
        if (baseDistribution != null && distribution != null &&
            !baseDistribution.equals(distribution, ignoreCase = true)) return null
        val configDistribution = wslDistributionId(configuredPath)
        return when {
            configDistribution != null && distribution != null &&
                !configDistribution.equals(distribution, ignoreCase = true) -> null
            normalized.startsWith("//") -> normalized
            configuredPath.startsWith('/') && distribution != null ->
                // UNC also reaches custom mount roots; getWindowsPath may discover mounts through a process.
                "//wsl.localhost/$distribution${path.normalize().toString().replace('\\', '/')}"
            path.isAbsolute -> path.normalize().toString().replace('\\', '/')
            relative -> base.toNioPath().resolve(path).normalize().toString().replace('\\', '/')
            // A target-native /... path on Windows must not become a path on the host's current drive.
            else -> null
        }
    }

    private fun unavailable(reason: BiomeConfigurationResolution.Reason) = BiomeConfigurationResolution.Unavailable(reason)
}
