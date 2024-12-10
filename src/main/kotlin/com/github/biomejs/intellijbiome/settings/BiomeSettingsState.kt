package com.github.biomejs.intellijbiome.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
enum class ConfigurationMode {
    DISABLED,
    AUTOMATIC,
    MANUAL
}

@Service
@ApiStatus.Internal
class BiomeSettingsState : BaseState() {
    var executablePath by string()
    var configPath by string()
    var filePattern by string(DEFAULT_FILE_PATTERN)
    var enableLspFormat by property(false)
    var applySafeFixesOnSave by property(false)
    var applyUnsafeFixesOnSave by property(false)
    var configurationMode by enum(ConfigurationMode.AUTOMATIC)

    companion object {
        const val DEFAULT_FILE_PATTERN = "**/*.{js,mjs,cjs,ts,jsx,tsx,cts,json,jsonc,vue,svelte,astro,css}"
    }
}
