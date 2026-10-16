package com.github.biomejs.intellijbiome.services

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.execute
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.command.undo.UnexpectedUndoException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.NewVirtualFileSystem
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.platform.lsp.util.getRangeInDocument
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.*
import java.io.IOException

@Service(Service.Level.PROJECT)
class BiomeServerService internal constructor(
    private val project: Project,
    private val requests: BiomeLspRequests,
) {
    constructor(project: Project) : this(project, DefaultBiomeLspRequests)

    private val groupId = "Biome"

    enum class Feature {
        Format, ApplySafeFixes, SortImports
    }

    enum class Outcome {
        Changed, Unchanged, Unavailable, Stale, NotApplied, PartiallyChanged
    }

    companion object {
        fun getInstance(project: Project): BiomeServerService = project.getService(BiomeServerService::class.java)
    }

    private fun getServer(file: VirtualFile): LspServer? =
        LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
            .firstOrNull { server -> server.state == LspServerState.Running && server.descriptor.isSupportedFile(file) }

    suspend fun applySafeFixes(document: Document): Outcome =
        executeFeatures(document, EnumSet.of(Feature.ApplySafeFixes))

    suspend fun sortImports(document: Document): Outcome =
        executeFeatures(document, EnumSet.of(Feature.SortImports))

    suspend fun format(document: Document): Outcome =
        executeFeatures(document, EnumSet.of(Feature.Format))

    fun restartBiomeServer() {
        LspServerManager.getInstance(project).stopAndRestartIfNeeded(BiomeLspServerSupportProvider::class.java)
    }

    fun stopBiomeServer() {
        LspServerManager.getInstance(project).stopServers(BiomeLspServerSupportProvider::class.java)
    }

    suspend fun executeFeatures(document: Document, features: EnumSet<Feature>): Outcome {
        val file = readAction { FileDocumentManager.getInstance().getFile(document) } ?: return Outcome.Unavailable
        val server = getServer(file) ?: return Outcome.Unavailable
        var changed = false
        var skipped = false
        val commandName = BiomeBundle.message("biome.run.biome.check.with.features",
            features.joinToString(prefix = "(", postfix = ")") { it.toString().lowercase() })

        for ((feature, kind) in listOf(
            Feature.ApplySafeFixes to "source.fixAll.biome",
            Feature.SortImports to "source.organizeImports.biome",
        )) {
            if (!features.contains(feature)) continue
            val (stamp, params) = readAction {
                document.modificationStamp to CodeActionParams(
                    server.getDocumentIdentifier(file),
                    getLsp4jRange(document, 0, document.textLength),
                    CodeActionContext().apply {
                        diagnostics = emptyList()
                        only = listOf(kind)
                        triggerKind = CodeActionTriggerKind.Automatic
                    },
                )
            }
            val actions = requests.codeActions(server, params)
            if (actions == null && server.state != LspServerState.Running) {
                return if (changed) Outcome.PartiallyChanged else Outcome.Unavailable
            }
            when (applyCodeActions(server, document, file, stamp, commandName, actions.orEmpty())) {
                Outcome.Changed -> changed = true
                Outcome.NotApplied -> skipped = true
                Outcome.PartiallyChanged -> {
                    changed = true
                    skipped = true
                }
                Outcome.Stale -> return Outcome.Stale
                else -> Unit
            }
        }

        if (features.contains(Feature.Format)) {
            val (stamp, params) = readAction {
                document.modificationStamp to DocumentFormattingParams(
                    server.getDocumentIdentifier(file),
                    FormattingOptions(2, false), // Biome does not use these options.
                )
            }
            val edits = requests.formatting(server, params)
            if (edits == null && server.state != LspServerState.Running) {
                return if (changed) Outcome.PartiallyChanged else Outcome.Unavailable
            }
            if (edits != null) {
                if (!applyIfCurrent(document, file, stamp, commandName) {
                        val before = document.text
                        val separator = file.detectedLineSeparator
                        applyFormatting(document, file, edits)
                        changed = changed || document.text != before || file.detectedLineSeparator != separator
                    }) return Outcome.Stale
            }
        }
        return when {
            skipped && changed -> Outcome.PartiallyChanged
            skipped -> Outcome.NotApplied
            changed -> Outcome.Changed
            else -> Outcome.Unchanged
        }
    }

    private suspend fun applyCodeActions(
        server: LspServer,
        document: Document,
        file: VirtualFile,
        stamp: Long,
        commandName: String,
        actions: List<Either<Command, CodeAction>>,
    ): Outcome {
        val context = currentCoroutineContext()
        var expectedStamp = stamp
        var changed = false
        var skipped = false
        var stale = false
        fun isCurrent(): Boolean {
            context.ensureActive()
            ProgressManager.checkCanceled()
            if (project.isDisposed) throw ProcessCanceledException()
            return document.modificationStamp == expectedStamp && isFileCurrent(file)
        }

        if (!readAction { isCurrent() }) return Outcome.Stale
        // Native intentions prepare write access before entering their own write action.
        // Keep the undo command, but let permission dialogs run without a write lock.
        withContext(Dispatchers.EDT) {
            CommandProcessor.getInstance().executeCommand(project, {
                for (result in actions) {
                    val codeAction = result.right
                    val edit = codeAction?.edit
                    // Commands and deferred actions have no tracked synchronous completion here.
                    if (edit == null || codeAction.command != null) {
                        skipped = true
                        continue
                    }
                    var applied = edit.changes.isNullOrEmpty() && edit.documentChanges.isNullOrEmpty()
                    val action = object : LspIntentionAction(server, codeAction) {
                        override fun applyWorkspaceEdit(workspaceEdit: WorkspaceEdit, uriToDocumentMap: Map<String, Document>) {
                            // Preparation can pump UI events or outlive the request deadline.
                            if (!isCurrent()) {
                                stale = true
                                return
                            }
                            val before = document.text
                            super.applyWorkspaceEdit(workspaceEdit, uriToDocumentMap)
                            applied = true
                            changed = changed || document.text != before
                            expectedStamp = document.modificationStamp
                        }
                    }
                    val available = ReadAction.compute<Boolean, RuntimeException> {
                        if (!isCurrent()) {
                            stale = true
                            false
                        } else action.isAvailable()
                    }
                    if (stale) break
                    if (available) action.invoke(file)
                    context.ensureActive()
                    if (stale) break
                    if (!available || !applied) skipped = true
                }
            }, commandName, groupId)
        }
        return when {
            stale -> Outcome.Stale
            skipped && changed -> Outcome.PartiallyChanged
            skipped -> Outcome.NotApplied
            changed -> Outcome.Changed
            else -> Outcome.Unchanged
        }
    }

    private suspend fun applyIfCurrent(
        document: Document,
        file: VirtualFile,
        stamp: Long,
        commandName: String,
        apply: () -> Unit,
    ): Boolean {
        val context = currentCoroutineContext()
        context.ensureActive()
        return WriteCommandAction.writeCommandAction(project).withName(commandName).withGroupId(groupId).execute {
            context.ensureActive()
            ProgressManager.checkCanceled()
            if (project.isDisposed) throw ProcessCanceledException()
            if (document.modificationStamp != stamp || !isFileCurrent(file)) false
            else {
                apply()
                true
            }
        }
    }

    private fun applyFormatting(document: Document, file: VirtualFile, edits: List<TextEdit>) {
        val original = document.text
        val replacements = edits.map { edit ->
            val range = getRangeInDocument(document, edit.range) ?: return
            range to edit.newText
        }.sortedWith(compareBy({ it.first.startOffset }, { it.first.endOffset }))
        val output = StringBuilder(original.length)
        val replacementEnds = IntArray(replacements.size)
        var offset = 0
        replacements.forEachIndexed { index, (range, text) ->
            if (range.startOffset < offset) return
            output.append(original, offset, range.startOffset).append(text)
            replacementEnds[index] = output.length
            offset = range.endOffset
        }
        output.append(original, offset, original.length)
        // Even an empty edit list describes complete LF output from the normalized document.
        val rawOutput = output.toString()
        replacements.indices.reversed().forEach { index ->
            val (range, text) = replacements[index]
            // Keep the LF when a CRLF pair crosses a replacement boundary. Original
            // document text already uses LF, so only an inserted trailing CR can split it.
            val normalized = StringUtil.convertLineSeparators(
                if (text.endsWith('\r') && rawOutput.getOrNull(replacementEnds[index]) == '\n') text.dropLast(1)
                else text)
            if (!StringUtil.equals(range.subSequence(original), normalized)) {
                document.replaceString(range.startOffset, range.endOffset, normalized)
            }
        }
        StringUtil.detectSeparators(rawOutput)?.let { applyLineSeparator(document, file, it.separatorString) }
    }

    private fun applyLineSeparator(document: Document, file: VirtualFile, separator: String) {
        if (file.detectedLineSeparator == separator) return
        val manager = FileDocumentManager.getInstance()
        val previousSeparator = manager.getLineSeparator(file, project)
        if (!changeLineSeparatorIfCurrent(document, file, separator, manager)) return
        UndoManager.getInstance(project).undoableActionPerformed(object : BasicUndoableAction(document) {
            override fun undo() = restoreSeparator(previousSeparator)
            override fun redo() = restoreSeparator(separator)

            private fun restoreSeparator(value: String) {
                try {
                    if (!changeLineSeparatorIfCurrent(document, file, value, manager)) {
                        throw UnexpectedUndoException(BiomeBundle.message("biome.undo.file.changed", file.presentableUrl))
                    }
                } catch (failure: IOException) {
                    throw UnexpectedUndoException(failure.message ?: failure.toString()).apply { initCause(failure) }
                }
            }
        })
    }

    private fun changeLineSeparatorIfCurrent(
        document: Document,
        file: VirtualFile,
        separator: String,
        manager: FileDocumentManager,
    ): Boolean {
        if (!isFileCurrent(file)) return false
        val textChanged = manager.isDocumentUnsaved(document) &&
            !StringUtil.equals(document.charsSequence,
                LoadTextUtil.getTextByBinaryPresentation(file.contentsToByteArray(), file, false, false))
        if (textChanged) {
            // Text edits and their separator metadata reach disk in the platform's final save.
            file.detectedLineSeparator = separator
        } else {
            // Equal normalized text is skipped by the final save. The direct conversion
            // must check disk freshness too; cached VFS bytes can hide an external write.
            if (!isFileCurrent(file)) return false
            LoadTextUtil.changeLineSeparators(project, file, separator, manager)
        }
        return true
    }

    private fun isFileCurrent(file: VirtualFile): Boolean {
        if (!file.isValid) return false
        val fileSystem = file.fileSystem
        return fileSystem !is NewVirtualFileSystem || file.timeStamp == fileSystem.getTimeStamp(file)
    }

    fun notifyRestart() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Biome")
            .createNotification(
                BiomeBundle.message("biome.language.server.restarted"),
                "",
                NotificationType.INFORMATION
            )
            .notify(project)
    }
}
