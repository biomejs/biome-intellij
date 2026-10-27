package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.lsp.BiomeLspLifecycleService
import com.github.biomejs.intellijbiome.lsp.BiomeLspDocumentListener
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.PublishDiagnosticsParams

@Service(Service.Level.PROJECT)
internal class BiomeProjectViewActionRunner(private val project: Project, private val scope: CoroutineScope) {
    fun run(title: String, operation: suspend () -> Unit) {
        scope.launch {
            withBackgroundProgress(project, title) { operation() }
        }
    }
}

/** Opens the selected file and waits for Biome to finish processing a newly opened document. */
internal suspend fun prepareBiomeProjectViewFile(project: Project, file: VirtualFile, document: Document): Boolean {
    if (project.isDisposed) return false
    val lifecycle = project.service<BiomeLspLifecycleService>()
    val request = lifecycle.requestStamp()
    val descriptor = readAction {
        if (project.isDisposed || !file.isValid || !file.isWritable || !document.isWritable ||
            FileDocumentManager.getInstance().getFile(document) != file) return@readAction null
        var candidate: LspServerDescriptor? = null
        // Discovery checks the owning root's Stop fence without resuming it or starting a process.
        BiomeLspServerSupportProvider().discoverFile(project, file, object : LspServerSupportProvider.LspServerStarter {
            override fun ensureServerStarted(descriptor: LspServerDescriptor) {
                candidate = descriptor
            }
        }, request, includeNestedRoots = false)
        candidate
    } ?: return false
    val roots = descriptor.roots.toList()
    val uri = descriptor.getFileUri(file)
    fun permitted(): Boolean = !project.isDisposed && roots.all {
        lifecycle.permits(it, request) && !lifecycle.isStopped(it)
    }

    val ready = CompletableDeferred<Boolean>()
    val lifetime = Disposer.newDisposable()
    try {
        val started = withContext(Dispatchers.EDT) {
            if (!permitted() || !file.isValid || !file.isWritable || !document.isWritable) return@withContext false
            val manager = LspServerManager.getInstance(project)
            project.messageBus.connect(lifetime).subscribe(BiomeLspDocumentListener.TOPIC, object : BiomeLspDocumentListener {
                override fun diagnosticsPublished(published: BiomeLspServerDescriptor, params: PublishDiagnosticsParams) {
                    if (params.uri != uri || published.roots.none { it in roots }) return
                    val server = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
                        .firstOrNull { it.descriptor === published && it.state == LspServerState.Running } ?: return
                    val version = params.version
                    if (version != null && version != server.getDocumentVersion(document)) return
                    // Even empty diagnostics acknowledge that Biome processed the document.
                    ready.complete(permitted())
                }

                override fun serverStopped(stopped: BiomeLspServerDescriptor) {
                    if (stopped.roots.any { it in roots }) ready.complete(false)
                }
            })
            val editors = FileEditorManager.getInstance(project)
            val existingServer = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
                .any { it.state == LspServerState.Running && it.descriptor.roots.any { root -> root in roots } }
            if (editors.isFileOpen(file) && existingServer) {
                ready.complete(permitted())
            } else {
                if (!permitted()) return@withContext false
                manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
                if (!permitted()) return@withContext false
                // Opening the editor is intentional: the SDK owns didOpen/didChange synchronization.
                if (editors.openFile(file, true).isEmpty()) return@withContext false
            }
            permitted()
        }
        if (!started || !ready.await()) return false
        return readAction {
            permitted() && file.isValid && file.isWritable && document.isWritable &&
                FileDocumentManager.getInstance().getFile(document) == file
        }
    } finally {
        Disposer.dispose(lifetime)
    }
}
