package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.extensions.isBiomeConfigFile
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.lsp.api.LspServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun BiomeSettings.usesConfigDiscovery(): Boolean =
    configurationMode == ConfigurationMode.AUTOMATIC ||
        (configurationMode == ConfigurationMode.MANUAL && configPath.isEmpty())

/** Retries discovery for open files; existing workspaces reload their configuration in Biome. */
@Service(Service.Level.PROJECT)
class BiomeConfigDiscoveryService(private val project: Project, private val scope: CoroutineScope) {
    private var pendingRescan: Job? = null

    init {
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (project.isDisposed || !BiomeSettings.getInstance(project).usesConfigDiscovery()) return
                    val roots = project.getBaseDirectories()
                    val relevant = events.any { event ->
                        changedConfig(event)?.let { file -> roots.any { VfsUtilCore.isAncestor(it, file, true) } } == true
                    }
                    if (relevant) requestRescan()
                }
            })
    }

    @Synchronized
    private fun requestRescan() {
        val lifecycle = project.service<BiomeLspLifecycleService>()
        val request = lifecycle.requestStamp()
        pendingRescan?.cancel()
        pendingRescan = scope.launch {
            delay(100)
            withContext(Dispatchers.EDT) {
                if (project.isDisposed || !BiomeSettings.getInstance(project).usesConfigDiscovery() ||
                    !lifecycle.isCurrent(request)) return@withContext
                // The SDK owns its asynchronous scan. Provider Stop gates also apply after dispatch.
                LspServerManager.getInstance(project).startServersIfNeeded(BiomeLspServerSupportProvider::class.java)
            }
        }
    }

    private fun changedConfig(event: VFileEvent): VirtualFile? {
        val file = when (event) {
            is VFileCopyEvent -> event.findCreatedFile()
            is VFileCreateEvent, is VFileContentChangeEvent, is VFileMoveEvent -> event.file
            is VFilePropertyChangeEvent -> event.file.takeIf { event.propertyName == VirtualFile.PROP_NAME }
            else -> null
        }
        return file?.takeIf { it.isValid && !it.isDirectory && it.isInLocalFileSystem && it.isBiomeConfigFile() }
    }
}
