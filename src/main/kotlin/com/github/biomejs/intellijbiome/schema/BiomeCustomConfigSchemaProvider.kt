package com.github.biomejs.intellijbiome.schema

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.github.biomejs.intellijbiome.wslDistributionId
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.jetbrains.jsonSchema.extension.ContentAwareJsonSchemaFileProvider
import com.jetbrains.jsonSchema.remote.JsonFileResolver
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Last-resort association for a manually selected, custom-named configuration. */
class BiomeCustomConfigSchemaProvider : ContentAwareJsonSchemaFileProvider {
    override fun getSchemaFile(file: PsiFile): VirtualFile? {
        val json = file as? JsonFile ?: return null
        // Presence matters: an unresolved, empty or invalid explicit schema still belongs to the user.
        if ((json.topLevelValue as? JsonObject)?.findProperty("\$schema") != null) return null
        val config = file.originalFile.virtualFile?.takeIf { it.isValid } ?: return null
        if (config.extension !in listOf("json", "jsonc") || config.name in listOf("biome.json", "biome.jsonc")) return null
        val settings = BiomeSettings.getInstance(file.project)
        if (settings.configurationMode != ConfigurationMode.MANUAL || settings.configPath.isBlank()) return null
        val configured = FileUtil.toSystemIndependentName(settings.configPath)
        val path = try {
            Path.of(configured)
        } catch (_: InvalidPathException) {
            return null
        }
        val matches = when {
            configured.startsWith('/') || path.isAbsolute -> matchesPath(config.path, configured)
            path.root == null -> file.project.getBaseDirectories().any { matchesPath(config.path, "${it.path}/$configured") }
            else -> false
        }
        return if (matches) JsonFileResolver.urlToFile("https://biomejs.dev/schemas/2.5.15/schema.json") else null
    }

    private fun matchesPath(actual: String, selected: String): Boolean {
        val actualPath = schemaConfigurationPath(actual)
        val selectedPath = schemaConfigurationPath(selected)
        val actualWsl = wslDistributionId(actualPath) != null
        if (actualWsl && selectedPath.startsWith('/') && !selectedPath.startsWith("//")) {
            // A Linux-style selection identifies the same path in any opened WSL distribution.
            val linuxPath = "/" + actualPath.removePrefix("//").substringAfter('/').substringAfter('/')
            return linuxPath == FileUtil.toCanonicalPath(selectedPath)
        }
        return if (actualWsl || wslDistributionId(selectedPath) != null) {
            actualPath == selectedPath
        } else FileUtil.pathsEqual(FileUtil.toCanonicalPath(actualPath), FileUtil.toCanonicalPath(selectedPath))
    }
}

/** WSL UNC aliases identify one Linux file; preserve the case-sensitive path below the distribution. */
internal fun schemaConfigurationPath(path: String): String {
    val normalized = FileUtil.toSystemIndependentName(path)
    val distribution = wslDistributionId(normalized) ?: return normalized
    val suffix = normalized.removePrefix("//").substringAfter('/').substringAfter('/').trimStart('/')
    return "//wsl.localhost/${distribution.lowercase()}${FileUtil.toCanonicalPath("/$suffix")}"
}
