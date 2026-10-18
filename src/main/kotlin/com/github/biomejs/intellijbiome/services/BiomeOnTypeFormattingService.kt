package com.github.biomejs.intellijbiome.services

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.lsp.BiomeLspDocumentListener
import com.github.biomejs.intellijbiome.lsp.BiomeLspLifecycleService
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.injected.editor.DocumentWindow
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.execute
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.NewVirtualFileSystem
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.util.applyTextEdits
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getRangeInDocument
import kotlinx.coroutines.*
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.TextEdit
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
internal class BiomeOnTypeFormattingService internal constructor(
    private val project: Project,
    private val scope: CoroutineScope,
    private val servers: () -> Collection<LspServer>,
    private val request: suspend (LspServer, DocumentOnTypeFormattingParams) -> List<TextEdit>?,
) {
    constructor(project: Project, scope: CoroutineScope) : this(project, scope, {
        LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
    }, { server, params -> server.sendRequest { it.textDocumentService.onTypeFormatting(params) } })

    private val pending = ConcurrentHashMap<Editor, Job>()

    init {
        project.messageBus.connect(scope).subscribe(FileEditorManagerListener.Before.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener.Before {
                override fun beforeFileClosed(source: FileEditorManager, file: VirtualFile) {
                    pending.forEach { (editor, job) ->
                        if (FileDocumentManager.getInstance().getFile(editor.document) == file) job.cancel()
                    }
                }
            })
        project.messageBus.connect(scope).subscribe(BiomeLspDocumentListener.TOPIC, object : BiomeLspDocumentListener {
            override fun serverStopped(descriptor: BiomeLspServerDescriptor) {
                // The lifecycle generation is project-wide too; only our typing jobs are cancelled.
                pending.values.forEach { it.cancel() }
            }
        })
    }

    /** Called after insertion; server selection and the RPC run outside the typing callback. */
    fun onTyped(editor: Editor, character: Char): Job? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        pending.remove(editor)?.cancel()
        if (!allowed(editor)) return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        if (!file.isValid || !file.isWritable || !file.isInLocalFileSystem || file is VirtualFileWindow) return null
        val stamp = editor.document.modificationStamp
        val path = file.path
        val caret = editor.caretModel.offset
        val generation = project.service<BiomeLspLifecycleService>().requestStamp()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val selected = readAction {
                    if (!allowed(editor) || editor.document.modificationStamp != stamp) return@readAction null
                    val server = servers().singleOrNull {
                        it.state == LspServerState.Running && it.descriptor is BiomeLspServerDescriptor &&
                            it.descriptor.isSupportedFile(file)
                    } ?: return@readAction null
                    val descriptor = server.descriptor as BiomeLspServerDescriptor
                    val lifecycle = project.service<BiomeLspLifecycleService>()
                    if (!lifecycle.isCurrent(generation) || descriptor.roots.any { lifecycle.isStopped(it) }) return@readAction null
                    val registration = descriptor.onTypeFormatting.select(
                        server.initializeResult?.capabilities?.documentOnTypeFormattingProvider, character) ?: return@readAction null
                    val options = com.intellij.application.options.CodeStyle.getIndentOptions(project, file)
                    val params = DocumentOnTypeFormattingParams(server.getDocumentIdentifier(file),
                        FormattingOptions(options.TAB_SIZE, !options.USE_TAB_CHARACTER),
                        getLsp4jPosition(editor.document, caret), character.toString())
                    Selection(server, descriptor, registration, params)
                } ?: return@launch
                val edits = withTimeoutOrNull(5_000) { request(selected.server, selected.params) } ?: return@launch
                if (edits.isEmpty()) return@launch
                val context = currentCoroutineContext()
                WriteCommandAction.writeCommandAction(project)
                    .withName(BiomeBundle.message("biome.format.on.type.command")).execute<Unit> {
                        context.ensureActive()
                        val lifecycle = project.service<BiomeLspLifecycleService>()
                        synchronized(lifecycle) {
                            selected.descriptor.onTypeFormatting.ifCurrent(selected.registration) {
                                // Check inside the command body: command-start listeners can invalidate a returned response.
                                if (!allowed(editor) || !lifecycle.isCurrent(generation) ||
                                    !file.isValid || !file.isWritable || file.path != path || !isFileCurrent(file) ||
                                    editor.document.modificationStamp != stamp ||
                                    editor.caretModel.offset != caret || selected.server.state != LspServerState.Running ||
                                    selected.server.descriptor !== selected.descriptor ||
                                    servers().none { it === selected.server } ||
                                    selected.descriptor.roots.any { lifecycle.isStopped(it) } ||
                                    !selected.descriptor.isSupportedFile(file)) return@ifCurrent
                                val ranges = edits.map { getRangeInDocument(editor.document, it.range) ?: return@ifCurrent }
                                    .sortedBy { it.startOffset }
                                if (ranges.zipWithNext().any { (a, b) -> a.endOffset > b.startOffset }) return@ifCurrent
                                applyTextEdits(editor.document, edits)
                            }
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure is ControlFlowException) throw failure
                logger<BiomeOnTypeFormattingService>().debug("On-type formatting request failed", failure)
            }
        }
        pending[editor] = job
        job.invokeOnCompletion { pending.remove(editor, job) }
        job.start()
        return job
    }

    private fun allowed(editor: Editor): Boolean = !project.isDisposed && !project.isDefault &&
        editor.project === project && !editor.isDisposed && !editor.isViewer && editor.document !is DocumentWindow &&
        editor.document.isWritable && BiomeSettings.getInstance(project).formatOnType &&
        editor.caretModel.caretCount == 1 && !editor.selectionModel.hasSelection() &&
        LookupManager.getActiveLookup(editor) == null && TemplateManager.getInstance(project).getActiveTemplate(editor) == null

    private fun isFileCurrent(file: VirtualFile): Boolean =
        (file.fileSystem as? NewVirtualFileSystem)?.let { file.timeStamp == it.getTimeStamp(file) } ?: true

    private data class Selection(val server: LspServer, val descriptor: BiomeLspServerDescriptor,
        val registration: Long, val params: DocumentOnTypeFormattingParams)
}
