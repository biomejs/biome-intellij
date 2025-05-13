@file:Suppress("UnstableApiUsage")

package com.github.biomejs.intellijbiome.listeners

import com.github.biomejs.intellijbiome.BiomeConfig
import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.extensions.findBiomeConfigs
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.platform.lsp.api.LspServerManager
import kotlinx.coroutines.runBlocking

class BiomeBulkFileListener(private val project: Project) : BulkFileListener {
    val biomeService = BiomeServerService.getInstance(project)

    override fun after(events: List<VFileEvent>) {
        var isConfigContentChange = false
        var isConfigChange = false

        for (event in events) {
            if (event.isBiomeConfigChange()) {
                isConfigChange = true
                break
            }
            if (event.isBiomeConfigContentChange()) {
                isConfigContentChange = true
            }
        }

        if (isConfigChange) {
            val roots = project.findBiomeConfigs().map { it.parent }.distinct()

            // Restart with updated root list
            val pkg = BiomePackage(project)
            val executable = pkg.binaryPath(null) ?: return
            val configPath = pkg.configPath()

            ApplicationManager.getApplication().executeOnPooledThread {
                val version = runBlocking { BiomePackage(project).versionNumber() }

                val descriptor = BiomeLspServerDescriptor(project = project,
                    executable = executable,
                    version = version,
                    configPath = configPath,
                    roots = roots.toTypedArray())

                // If we end up here following a configuration change, we need to wait
                // for the notification to be processed before we can stop the LSP session,
                // otherwise we will get an error. This is a workaround for a race condition
                // that occurs when the configuration change notification is sent while the
                // LSP session is already stopped.
                Thread.sleep(1000)
                biomeService.stopBiomeServer()
                LspServerManager.getInstance(project)
                    .ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
            }
        } else if (isConfigContentChange) {
            ApplicationManager.getApplication().executeOnPooledThread {
                // If we end up here following a configuration change, we need to wait
                // for the notification to be processed before we can stop the LSP session,
                // otherwise we will get an error. This is a workaround for a race condition
                // that occurs when the configuration change notification is sent while the
                // LSP session is already stopped.
                Thread.sleep(1000)
                biomeService.restartBiomeServer()
            }
        }
    }
}

private fun VFileEvent.isBiomeConfigChange(): Boolean {
    if (this is VFilePropertyChangeEvent && propertyName == VirtualFile.PROP_NAME) {
        val oldName = oldValue as? String
        val newName = newValue as? String
        return oldName in BiomeConfig.validFileNames || newName in BiomeConfig.validFileNames
    }

    val file = this.file ?: return false
    return when (this) {
        is VFileCreateEvent, is VFileMoveEvent, is VFileCopyEvent, is VFileDeleteEvent -> BiomeConfig.isBiomeConfigFile(
            file)

        else -> false
    }
}

private fun VFileEvent.isBiomeConfigContentChange(): Boolean =
    this is VFileContentChangeEvent && file.let { BiomeConfig.isBiomeConfigFile(it) }
