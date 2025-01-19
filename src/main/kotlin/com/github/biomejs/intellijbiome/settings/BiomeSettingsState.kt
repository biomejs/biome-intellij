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
    var formatOnSave by property(false)
    var enableLspFormat by property(false)
    var applySafeFixesOnSave by property(false)
    var sortImportOnSave by property(false)
    var configurationMode by enum(ConfigurationMode.AUTOMATIC)
    var supportedExtensions by list<String>()

    companion object {
        val DEFAULT_EXTENSION_LIST = listOf(
            ".astro", ".css", ".gql", ".graphql", ".js", ".mjs", ".cjs", ".jsx",
            ".json", ".jsonc", ".svelte", ".html", ".ts", ".mts", ".cts", ".tsx", ".vue"
        )
    }
}
