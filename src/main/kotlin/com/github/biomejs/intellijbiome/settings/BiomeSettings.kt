package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.services.BiomeServerService.Feature
import com.github.biomejs.intellijbiome.settings.BiomeSettingsState.Companion.DEFAULT_EXTENSION_LIST
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.*


@Service(Service.Level.PROJECT)
@State(name = "BiomeSettings", storages = [(Storage("biome.xml"))])
class BiomeSettings :
    SimplePersistentStateComponent<BiomeSettingsState>(BiomeSettingsState()) {
    var executablePath: String
        get() = state.executablePath ?: ""
        set(value) {
            state.executablePath = value
        }
    var configPath: String
        get() = state.configPath ?: ""
        set(value) {
            val file = File(value)
            if (file.isFile) {
                state.configPath = file.parentFile.path
                return
            }
            state.configPath = value
        }

    var supportedExtensions: MutableList<String>
        get() = state.supportedExtensions.takeIf { it.isNotEmpty() } ?: DEFAULT_EXTENSION_LIST.toMutableList()
        set(value) {
            state.supportedExtensions.clear()
            state.supportedExtensions.addAll(value)
        }

    var configurationMode: ConfigurationMode
        get() = state.configurationMode
        set(value) {
            state.configurationMode = value
        }

    var enableLspFormat: Boolean
        get() = isEnabled() && state.enableLspFormat
        set(value) {
            state.enableLspFormat = value
        }

    var formatOnSave: Boolean
        get() = isEnabled() && state.formatOnSave
        set(value) {
            state.formatOnSave = value
        }

    var sortImportOnSave: Boolean
        get() = isEnabled() && state.sortImportOnSave
        set(value) {
            state.sortImportOnSave = value
        }

    var applySafeFixesOnSave: Boolean
        get() = isEnabled() && state.applySafeFixesOnSave
        set(value) {
            state.applySafeFixesOnSave = value
        }

    fun getEnabledFeatures(): EnumSet<Feature> {
        val features = EnumSet.noneOf(Feature::class.java)
        if (formatOnSave) {
            features.add(Feature.Format)
        }
        if (applySafeFixesOnSave) {
            features.add(Feature.ApplySafeFixes)
        }
        if (sortImportOnSave) {
            features.add(Feature.SortImports)
        }
        return features
    }

    fun isEnabled(): Boolean {
        return configurationMode !== ConfigurationMode.DISABLED
    }

    fun fileSupported(file: VirtualFile): Boolean {
        val fileExtension = file.extension
        return if (fileExtension != null) {
            supportedExtensions.contains(".$fileExtension")
        } else {
            false
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): BiomeSettings = project.service()
    }
}
