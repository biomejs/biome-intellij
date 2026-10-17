package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomeIcons
import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lang.lsWidget.LanguageServicePopupSection
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class BiomeLspServerWidgetItem(server: LspServer, currentFile: VirtualFile?) :
    LspServerWidgetItem(server, currentFile, BiomeIcons.BiomeIcon, BiomeConfigurable::class.java) {

    override fun createStopOrRestartAction(): AnAction? {
        val action = super.createStopOrRestartAction() ?: return null
        val restart = widgetActionLocation == LanguageServicePopupSection.ForCurrentFile ||
            lspServer.state == LspServerState.ShutdownUnexpectedly
        return object : DumbAwareAction() {
            init {
                copyFrom(action)
            }

            override fun actionPerformed(e: AnActionEvent) {
                val project = lspServer.project
                if (project.isDisposed) return
                val lifecycle = project.service<BiomeLspLifecycleService>()
                if (!restart) {
                    lifecycle.stop(lspServer.descriptor.roots.toList())
                    action.actionPerformed(e)
                    return
                }
                val stamp = lifecycle.requestStamp()
                lspServer.descriptor.roots.forEach { lifecycle.resume(it, stamp) }
                // Newer SDKs may reuse this descriptor for native Restart.
                (lspServer.descriptor as? BiomeLspServerDescriptor)?.refreshStartRequest(stamp)
                action.actionPerformed(e)
                project.service<BiomeWidgetRestartService>().rediscoverOpenFiles(lspServer.descriptor, stamp)
            }
        }
    }
}

@Service(Service.Level.PROJECT)
internal class BiomeWidgetRestartService(private val project: Project, private val scope: CoroutineScope) {
    fun rediscoverOpenFiles(descriptor: LspServerDescriptor, requestStamp: Long) {
        // NodeTargetRun retains the construction-time progress indicator. Use the SDK's
        // coroutine read-action path; NBRA cancels its temporary indicator on completion.
        scope.launch {
            readAction {
                if (project.isDisposed) return@readAction
                val lifecycle = project.service<BiomeLspLifecycleService>()
                if (descriptor.roots.none { lifecycle.permits(it, requestStamp) && !lifecycle.isStopped(it) }) return@readAction
                val manager = LspServerManager.getInstance(project)
                val provider = BiomeLspServerSupportProvider()
                val starter = object : LspServerSupportProvider.LspServerStarter {
                    override fun ensureServerStarted(descriptor: LspServerDescriptor) {
                        if (descriptor.roots.any { !lifecycle.permits(it, requestStamp) || lifecycle.isStopped(it) }) return
                        manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
                    }
                }
                val index = ProjectFileIndex.getInstance(project)
                // The SDK skips every file under a surviving root before consulting isSupportedFile.
                // Retry this server's files directly so an outer root cannot hide its nested workspace.
                for (file in FileEditorManager.getInstance(project).openFiles) {
                    ProgressManager.checkCanceled()
                    if (file.isValid && file.isInLocalFileSystem && index.isInContent(file) &&
                        descriptor.isSupportedFile(file)) {
                        provider.discoverFile(project, file, starter, requestStamp)
                    }
                }
            }
        }
    }
}
