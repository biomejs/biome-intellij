package com.github.biomejs.intellijbiome.services

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.lsp.BiomeLspDocumentListener
import com.github.biomejs.intellijbiome.lsp.BiomeLspLifecycleService
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor
import com.github.biomejs.intellijbiome.lsp.BiomeTroubleshootingText
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.ConfigurationItem
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
internal class BiomeTroubleshootingService internal constructor(
    private val project: Project,
    private val scope: CoroutineScope,
    private val requests: BiomeLspRequests,
    private val timeoutMs: Long,
    private val servers: () -> Collection<LspServer>,
) {
    constructor(project: Project, scope: CoroutineScope) : this(project, scope, DefaultBiomeLspRequests, 5_000, {
        LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
    })

    data class Target(val server: LspServer, val descriptor: LspServerDescriptor, val file: VirtualFile?,
        val filePath: String?, val generation: Long)
    data class Report(val text: String, val target: Target?, val generation: Long)

    private val pending = ConcurrentHashMap<Job, LspServerDescriptor>()

    init {
        project.messageBus.connect(scope).subscribe(BiomeLspDocumentListener.TOPIC, object : BiomeLspDocumentListener {
            override fun serverStopped(descriptor: BiomeLspServerDescriptor) {
                pending.forEach { (job, owner) -> if (owner === descriptor) job.cancel() }
            }
        })
    }

    /** Inspection only: never ask the manager to start, discover or restart a server. */
    fun targets(file: VirtualFile?): List<Target> {
        if (project.isDisposed || project.isDefault || file?.isValid == false) return emptyList()
        val generation = project.service<BiomeLspLifecycleService>().requestStamp()
        return servers().filter {
            isRunning(it) && (file == null || it.descriptor.isSupportedFile(file))
        }.map { Target(it, it.descriptor, file, file?.path, generation) }
    }

    fun localReport(file: VirtualFile?): Report {
        val generation = project.service<BiomeLspLifecycleService>().requestStamp()
        val text = metadata(file, null)
        text.line(BiomeBundle.message("biome.troubleshooting.no.server"))
        return Report(text.toString(), null, generation)
    }

    suspend fun collect(target: Target): Report? {
        currentCoroutineContext().ensureActive()
        // The project scope owns disposal; cancelling the caller also cancels its individual request.
        val operation = scope.async(start = CoroutineStart.LAZY) { collectCurrent(target) }
        pending[operation] = target.descriptor
        return try {
            operation.await()
        } finally {
            pending.remove(operation)
            operation.cancel()
        }
    }

    private suspend fun collectCurrent(target: Target): Report? {
        val text = readAction {
            if (!isCurrent(target)) null else metadata(target.file, target.server)
        } ?: return null
        try {
            val response = withTimeoutOrNull(timeoutMs) { Response(requests.rage(target.server)) }
            currentCoroutineContext().ensureActive()
            if (!readAction { isCurrent(target) }) return null
            text.line("")
            text.line(BiomeBundle.message("biome.troubleshooting.shared"))
            if (response == null) text.line(BiomeBundle.message("biome.troubleshooting.timeout"))
            else if (response.value == null) text.line(BiomeBundle.message("biome.troubleshooting.response.unavailable"))
            else response.value.appendTo(text)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (failure is ControlFlowException) throw failure
            // Exception messages can contain arbitrary transport output; keep the preview bounded and predictable.
            text.line(BiomeBundle.message("biome.troubleshooting.failed"))
        }
        currentCoroutineContext().ensureActive()
        return if (readAction { isCurrent(target) }) Report(text.toString(), target, target.generation) else null
    }

    /** Final check on EDT, after progress/chooser work and immediately before opening the preview. */
    fun deliver(report: Report, show: (String) -> Unit): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val valid = ReadAction.compute<Boolean, RuntimeException> {
            !project.isDisposed && project.service<BiomeLspLifecycleService>().isCurrent(report.generation) &&
                (report.target == null || isCurrent(report.target))
        }
        if (!valid) return false
        show(report.text)
        return true
    }

    private fun isCurrent(target: Target): Boolean =
        !project.isDisposed && project.service<BiomeLspLifecycleService>().isCurrent(target.generation) &&
            isRunning(target.server) && target.server.descriptor === target.descriptor &&
            servers().any { it === target.server } &&
            (target.file == null || (target.file.isValid && target.file.path == target.filePath &&
                target.descriptor.isSupportedFile(target.file)))

    private fun isRunning(server: LspServer): Boolean = server.state == LspServerState.Running &&
        server.descriptor.roots.none { project.service<BiomeLspLifecycleService>().isStopped(it) }

    private fun metadata(file: VirtualFile?, server: LspServer?): BiomeTroubleshootingText {
        val text = BiomeTroubleshootingText()
        val application = ApplicationInfo.getInstance()
        text.pair("IDE", application.fullApplicationName + " (" + application.build.asString() + ")")
        text.pair("Plugin", PluginManagerCore.getPlugin(PluginId.getId("com.github.biomejs.intellijbiome"))?.version ?: "unknown")
        text.pair("OS", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
        val settings = BiomeSettings.getInstance(project)
        text.pair("Integration", settings.configurationMode.name)
        text.pair("Configured executable", settings.executablePath.ifBlank { "automatic discovery" })
        text.pair("Configured configuration", settings.configPath.ifBlank { "automatic discovery" })
        for ((name, values) in listOf(
            "LSP formatting" to (settings.state.enableLspFormat to settings.enableLspFormat),
            "Format on save" to (settings.state.formatOnSave to settings.formatOnSave),
            "Safe fixes on save" to (settings.state.applySafeFixesOnSave to settings.applySafeFixesOnSave),
            "Sort imports on save" to (settings.state.sortImportOnSave to settings.sortImportOnSave),
        )) text.pair(name, "raw=${values.first}, effective=${values.second}")
        file?.let { text.pair("Local file", it.path) }
        if (server != null) {
            val descriptor = server.descriptor
            text.pair("Server version", server.initializeResult?.serverInfo?.version ?: "unknown")
            val biome = descriptor as? BiomeLspServerDescriptor
            text.pair("Selected executable", biome?.selectedExecutable ?: "unknown")
            text.pair("Selected configuration", biome?.configurationPath?.takeIf { it.isNotBlank() } ?: "automatic discovery")
            for (root in descriptor.roots) {
                text.pair("Local root", root.path)
                text.pair("Target root URI", descriptor.getFileUri(root))
            }
            file?.let { text.pair("Target file URI", descriptor.getFileUri(it)) }
            val configuration = biome?.getWorkspaceConfiguration(ConfigurationItem().apply { section = "biome" })
            text.pair("Target configuration", configuration?.configurationPath ?: "automatic discovery")
        }
        return text
    }

    private data class Response(val value: com.github.biomejs.intellijbiome.lsp.BiomeRageResult?)
}
