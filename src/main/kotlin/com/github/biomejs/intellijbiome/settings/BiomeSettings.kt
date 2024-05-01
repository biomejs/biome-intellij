package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.Feature
import com.intellij.lang.javascript.linter.GlobPatternUtil
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

    var filePattern: String
        get() = state.filePattern ?: BiomeSettingsState.DEFAULT_FILE_PATTERN
        set(value) {
            state.filePattern = value
        }

    var configurationMode: ConfigurationMode
        get() = state.configurationMode
        set(value) {
            state.configurationMode = value
        }

    var formatOnSave: Boolean
        get() = isEnabled() && state.formatOnSave
        set(value) {
            state.formatOnSave = value
        }

    var applySafeFixesOnSave: Boolean
        get() = isEnabled() && state.applySafeFixesOnSave
        set(value) {
            state.applySafeFixesOnSave = value
        }

    var applyUnsafeFixesOnSave: Boolean
        get() = isEnabled() && state.applyUnsafeFixesOnSave
        set(value) {
            state.applyUnsafeFixesOnSave = value
        }

    fun getEnabledFeatures(): EnumSet<Feature> {
        val features = EnumSet.noneOf(Feature::class.java)
        if (formatOnSave) {
            features.add(Feature.Format)
        }
        if (applySafeFixesOnSave) {
            features.add(Feature.SafeFixes)
        }
        if (applyUnsafeFixesOnSave) {
            features.add(Feature.UnsafeFixes)
        }
        return features
    }

    fun isEnabled(): Boolean {
        return configurationMode !== ConfigurationMode.DISABLED
    }

    fun fileSupported(project: Project, file: VirtualFile): Boolean =
        GlobPatternUtil.isFileMatchingGlobPattern(project, filePattern, file)

    companion object {
        @JvmStatic
        fun getInstance(project: Project): BiomeSettings = project.service()
    }
}
