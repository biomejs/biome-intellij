package com.github.biomejs.intellijbiome.settings

import com.intellij.lang.javascript.linter.GlobPatternUtil
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile


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
            state.configPath = value
        }

    var formatFilePattern: String
        get() = state.formatFilePattern ?: BiomeSettingsState.DEFAULT_FILE_PATTERN
        set(value) {
            state.formatFilePattern = value
        }

    var lintFilePattern: String
        get() = state.lintFilePattern ?: BiomeSettingsState.DEFAULT_FILE_PATTERN
        set(value) {
            state.lintFilePattern = value
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

    fun isEnabled(): Boolean {
        return configurationMode !== ConfigurationMode.DISABLED
    }

    fun canFormat(project: Project, file: VirtualFile): Boolean =
        GlobPatternUtil.isFileMatchingGlobPattern(project, formatFilePattern, file)

    fun canLint(project: Project, file: VirtualFile): Boolean =
        GlobPatternUtil.isFileMatchingGlobPattern(project, lintFilePattern, file)

    companion object {
        @JvmStatic
        fun getInstance(project: Project): BiomeSettings = project.service()
    }
}
