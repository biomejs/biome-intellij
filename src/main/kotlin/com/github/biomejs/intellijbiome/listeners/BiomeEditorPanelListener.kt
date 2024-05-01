package com.github.biomejs.intellijbiome.listeners

import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project

// This implements a listener for file editor manager events.
// It listens for file selection changes in IDE and restarts LSP server if selected file in editor should be checked with different Biome config.
class BiomeEditorPanelListener(private val project: Project) : FileEditorManagerListener {

    private var currentConfigPath: String? = null

    // on selection change, check if the new file should not use different biome config.
    // if so, restart biome server to use the new config.
    override fun selectionChanged(fileEditorManagerEvent: FileEditorManagerEvent) {
        val settings = BiomeSettings.getInstance(project)
        val isEnabled = settings.isEnabled()
        if (fileEditorManagerEvent.newFile != null) {
            val newConfigPath = BiomePackage(project).configPath(fileEditorManagerEvent.newFile!!)
            val biomeServerService = project.service<BiomeServerService>()
            // stop biome LSP server if selected file does not have biome config.
            if (newConfigPath == null) {
                currentConfigPath = null
                biomeServerService.stopBiomeServer()
                return
            }
            if (isEnabled && currentConfigPath != newConfigPath) {
                currentConfigPath = newConfigPath
                biomeServerService.restartBiomeServer()
            }
        }
    }

    fun getCurrentConfigPath(): String? {
        return currentConfigPath
    }
}
