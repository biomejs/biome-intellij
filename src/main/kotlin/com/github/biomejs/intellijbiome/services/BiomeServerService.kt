package com.github.biomejs.intellijbiome.services

import com.intellij.codeStyle.AbstractConvertLineSeparatorsAction
import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.execute
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.util.LineSeparator
import org.eclipse.lsp4j.*
import java.util.*

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

    companion object {
        fun getInstance(project: Project): BiomeServerService = project.getService(BiomeServerService::class.java)
    }

    private fun getServer(file: VirtualFile): LspServer? =
        LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
            .firstOrNull { server -> server.descriptor.isSupportedFile(file) }

    suspend fun applySafeFixes(document: Document) {
        executeFeatures(document, EnumSet.of(Feature.ApplySafeFixes))
    }

    suspend fun sortImports(document: Document) {
        executeFeatures(document, EnumSet.of(Feature.SortImports))
    }

    suspend fun format(document: Document) {
        executeFeatures(document, EnumSet.of(Feature.Format))
    }

    fun restartBiomeServer() {
        LspServerManager.getInstance(project).stopAndRestartIfNeeded(BiomeLspServerSupportProvider::class.java)
    }

    fun stopBiomeServer() {
        LspServerManager.getInstance(project).stopServers(BiomeLspServerSupportProvider::class.java)
    }

    suspend fun executeFeatures(document: Document, features: EnumSet<Feature>) {
        val file = readAction { FileDocumentManager.getInstance().getFile(document) } ?: return
        val server = getServer(file) ?: return
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
            if (!applyIfCurrent(document, file, stamp, commandName) {
                    actions?.forEach { result ->
                        if (result.isRight && result.right.edit != null && result.right.command == null) {
                            val action = LspIntentionAction(server, result.right)
                            if (action.isAvailable()) action.invoke(file)
                        }
                    }
                }) return
        }

        if (features.contains(Feature.Format)) {
            val (stamp, params) = readAction {
                document.modificationStamp to DocumentFormattingParams(
                    server.getDocumentIdentifier(file),
                    FormattingOptions(2, false), // Biome does not use these options.
                )
            }
            val edits = requests.formatting(server, params)
            if (edits != null) {
                applyIfCurrent(document, file, stamp, commandName) { applyFormatting(document, file, edits) }
            }
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
            if (document.modificationStamp != stamp || !file.isValid) false
            else {
                apply()
                true
            }
        }
    }

    private fun applyFormatting(document: Document, file: VirtualFile, edits: List<TextEdit>) {
        var lineSeparator: LineSeparator? = null

        edits.asReversed().forEach {
            val range = getRangeInDocument(document, it.range) ?: return@forEach

            if (StringUtil.isEmpty(it.newText)) {
                document.deleteString(range.startOffset, range.endOffset)
            } else {
                val normalizedText = StringUtil.convertLineSeparators(it.newText)

                if (range.endOffset >= 0) {
                    if (range.length <= 0) {
                        document.insertString(range.startOffset, normalizedText)
                    } else {
                        document.replaceString(range.startOffset, range.endOffset, normalizedText)
                    }
                } else if (range.startOffset > 0) {
                    document.insertString(range.startOffset, normalizedText)
                } else if (!StringUtil.equals(document.charsSequence, normalizedText)) {
                    document.setText(normalizedText)
                }
            }

            StringUtil.detectSeparators(it.newText)?.apply {
                lineSeparator = this
            }
        }

        lineSeparator?.let { setDetectedLineSeparator(project, file, it) }
    }

    private fun setDetectedLineSeparator(
        project: Project,
        vFile: VirtualFile,
        newSeparator: LineSeparator?,
    ): Boolean {
        if (newSeparator != null) {
            val newSeparatorString: String = newSeparator.separatorString

            if (!StringUtil.equals(vFile.detectedLineSeparator, newSeparatorString)) {
                AbstractConvertLineSeparatorsAction.changeLineSeparators(project, vFile, newSeparatorString)
                return true
            }
        }
        return false
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
