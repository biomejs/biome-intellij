package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.*
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import com.github.biomejs.intellijbiome.extensions.findNearestBiomeConfig
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.UnregistrationParams
import com.intellij.platform.lsp.api.customization.LspOnTypeFormattingDisabled
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.util.concurrent.TimeUnit


class BiomeLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (project.isDisposed) return
        // Consume real-open intent even when current settings reject this callback.
        // Looking up an existing service preserves the early startup eligibility gate.
        val reopenRequest = project.getServiceIfCreated(BiomeLspLifecycleService::class.java)?.takeEditorOpen(file)
        val settings = BiomeSettings.getInstance(project)
        if (!settings.isEnabled() || !settings.fileSupported(file)) return
        val lifecycle = project.service<BiomeLspLifecycleService>()
        discoverFile(project, file, serverStarter, lifecycle.requestStamp(), reopenRequest = reopenRequest)
    }

    internal fun discoverFile(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
        requestStamp: Long,
        includeNestedRoots: Boolean = true,
        reopenRequest: Long? = null,
    ) {
        if (project.isDisposed) return
        val lifecycle = project.service<BiomeLspLifecycleService>()
        val settings = BiomeSettings.getInstance(project)
        if (!settings.isEnabled() || !settings.fileSupported(file)) return
        val biome = BiomePackage(project)
        val configPath = biome.configPath()

        // Finds the root directory of a Biome workspace. It's typically the parent directory of `biome.json`.
        // If no `biome.json` file found, nothing to do.
        val projectRootDir = project
            .getBaseDirectories()
            .find { VfsUtil.isUnder(file, setOf(it)) } ?: return

        if (settings.usesConfigDiscovery()) {
            project.service<BiomeConfigDiscoveryService>()
        }

        val root = if (configPath.isNullOrEmpty()) {
            file.findNearestBiomeConfig(projectRootDir)?.parent ?: return
        } else {
            // When using manual configuration, the root directory will be the project root.
            projectRootDir
        }

        if (reopenRequest != null) lifecycle.resume(root, reopenRequest)
        if (!lifecycle.permits(root, requestStamp) || lifecycle.isStopped(root)) return

        // Select the executable before SDK startup.
        val executable = biome.binaryPath(file) ?: return
        serverStarter.ensureServerStarted(BiomeLspServerDescriptor(project, root, executable, configPath, requestStamp))
        if (!includeNestedRoots || !configPath.isNullOrEmpty()) return

        // Public Restart skips later open files below the first collected root.
        // Its callback holds one descriptor, so submit independent descendants to the manager.
        val manager = LspServerManager.getInstance(project)
        val scheduledRoots = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
            .flatMapTo(mutableSetOf(root)) { it.descriptor.roots.toList() }
        val index = ProjectFileIndex.getInstance(project)
        for (openFile in FileEditorManager.getInstance(project).openFiles) {
            ProgressManager.checkCanceled()
            if (!openFile.isInLocalFileSystem || !index.isInContent(openFile) ||
                !VfsUtilCore.isAncestor(root, openFile, true)) continue
            if (!settings.fileSupported(openFile)) continue
            val nestedRoot = openFile.findNearestBiomeConfig(projectRootDir)?.parent ?: continue
            if (nestedRoot in scheduledRoots || !VfsUtilCore.isAncestor(root, nestedRoot, true)) continue
            if (lifecycle.isStopped(nestedRoot) || !lifecycle.permits(nestedRoot, requestStamp)) continue
            val nestedExecutable = biome.binaryPath(openFile) ?: continue
            scheduledRoots.add(nestedRoot)
            manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java,
                BiomeLspServerDescriptor(project, nestedRoot, nestedExecutable, configPath, requestStamp))
        }
    }

    override fun createLspServerWidgetItem(lspServer: LspServer,
        currentFile: VirtualFile?): LspServerWidgetItem =
        BiomeLspServerWidgetItem(lspServer, currentFile)
}

internal class BiomeLspServerDescriptor(
    project: Project,
    root: VirtualFile,
    private val executable: String,
    private val configPath: String?,
    @Volatile private var requestStamp: Long,
) : LspServerDescriptor(project, "Biome", root) {
    override val lsp4jServerClass = BiomeLsp4jServer::class.java
    internal val onTypeFormatting = BiomeOnTypeFormattingRegistration()

    // NodeTargetRun captures its construction-time progress indicator. A cancelled
    // discovery read must not cancel a later launch, which has its own lifecycle fence.
    private val targetRun: BiomeTargetRun = ProgressManager.getInstance().runProcess(Computable {
        BiomeTargetRunBuilder(project)
            .getBuilder(executable, root.path)
            .addParameters(listOf(ProcessCommandParameter.Value("lsp-proxy")))
            .build()
    }, EmptyProgressIndicator())

    /** Captured launch selection; inspection must never rediscover or construct another command. */
    internal val selectedExecutable: String get() = executable
    internal val configurationPath: String? get() = configPath

    internal fun refreshStartRequest(stamp: Long) {
        requestStamp = stamp
    }

    internal fun copyForStart(stamp: Long): BiomeLspServerDescriptor =
        BiomeLspServerDescriptor(project, roots.single(), executable, configPath, stamp)

    override fun isSupportedFile(file: VirtualFile): Boolean {
        return BiomeSettings.getInstance(project).fileSupported(file)
            && roots.any { root ->
                if (!file.toNioPath().startsWith(root.toNioPath())) return@any false
                if (!configPath.isNullOrEmpty()) return@any true
                val nestedOwner = LspServerManager.getInstance(project)
                    .getServersForProvider(BiomeLspServerSupportProvider::class.java)
                    .any { server -> server.descriptor.roots.any { otherRoot ->
                        otherRoot != root && otherRoot.toNioPath().startsWith(root.toNioPath()) &&
                            file.toNioPath().startsWith(otherRoot.toNioPath())
                    } }
                if (nestedOwner) return@any false
                val config = file.findNearestBiomeConfig(root)
                // Rejections are cached by the IDE. Preserve an established root while its
                // config is temporarily invalid, unless another independent root owns the file.
                config == null || config.parent == root || BiomeConfig.loadFromFile(config)?.isRootConfig() != true
            }
    }

    // IntelliJ falls back to the file extension as the language ID, which Biome doesn't
    // recognize for these extensions. Use the language IDs that Biome expects instead.
    override fun getLanguageId(file: VirtualFile): String =
        when (file.extension?.lowercase()) {
            "htm" -> "html"
            "svg" -> "xml"
            "gs" -> "javascript"
            "md" -> "markdown"
            "yml" -> "yaml"
            else -> super.getLanguageId(file)
        }

    override fun createCommandLine(): GeneralCommandLine {
        throw RuntimeException("Not expected to be called because startServerProcess() is overridden")
    }

    override fun startServerProcess(): OSProcessHandler {
        val lifecycle = project.service<BiomeLspLifecycleService>()
        if (!lifecycle.permits(roots.single(), requestStamp)) throw ProcessCanceledException()
        onTypeFormatting.start()
        val handler = targetRun.startProcess()
        var handedOff = false
        try {
            // Startup is queued before the SDK publishes the server in its write action.
            // Wait for that publication, then reject a process created after its server stopped.
            val active = ApplicationManager.getApplication().runReadAction(Computable {
                !project.isDisposed && lifecycle.permits(roots.single(), requestStamp) &&
                    LspServerManager.getInstance(project)
                    .getServersForProvider(BiomeLspServerSupportProvider::class.java)
                    .any { it.descriptor === this }
            })
            if (!active) throw ProcessCanceledException()
            handedOff = true
            return handler
        } finally {
            if (!handedOff) {
                if (!handler.isStartNotified) handler.startNotify()
                // This proxy never reached the SDK. EOF avoids killing its shared daemon.
                try {
                    handler.processInput.close()
                } catch (_: IOException) {
                    // The process may have already exited.
                }
                try {
                    if (!handler.process.waitFor(1_000, TimeUnit.MILLISECONDS)) {
                        forceStopUnclaimedProcess(handler)
                        handler.process.waitFor(1_000, TimeUnit.MILLISECONDS)
                    }
                } catch (_: InterruptedException) {
                    forceStopUnclaimedProcess(handler)
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun forceStopUnclaimedProcess(handler: OSProcessHandler) {
        if (handler is BiomeLspProxyProcessHandler) {
            // General retains its raw Process, so use the handler's captured proxy ownership.
            handler.killProcess()
        } else {
            // The local Node route already wraps its Process; preserve other routes' cleanup.
            handler.process.destroyForcibly()
        }
    }

    override fun getFilePath(file: VirtualFile): String =
        targetRun.toTargetPath(file.path)

    override fun findLocalFileByPath(path: String): VirtualFile? =
        super.findLocalFileByPath(targetRun.toLocalPath(path))

    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient =
        super.createLsp4jClient(object : LspServerNotificationsHandler by handler {
            override fun registerCapability(params: RegistrationParams): java.util.concurrent.CompletableFuture<Void> {
                val epoch = onTypeFormatting.epoch()
                return handler.registerCapability(params).thenApply {
                    onTypeFormatting.register(params, epoch)
                    it
                }
            }

            override fun unregisterCapability(params: UnregistrationParams): java.util.concurrent.CompletableFuture<Void> {
                val epoch = onTypeFormatting.epoch()
                return handler.unregisterCapability(params).thenApply {
                    onTypeFormatting.unregister(params, epoch)
                    it
                }
            }

            override fun publishDiagnostics(params: PublishDiagnosticsParams) {
                handler.publishDiagnostics(params)
                if (!project.isDisposed) {
                    project.messageBus.syncPublisher(BiomeLspDocumentListener.TOPIC)
                        .diagnosticsPublished(this@BiomeLspServerDescriptor, params)
                }
            }
        })

    override val lspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) {
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(BiomeLspDocumentListener.TOPIC)
                    .serverInitialized(this@BiomeLspServerDescriptor)
            }
        }

        override fun serverStopped(shutdownNormally: Boolean) {
            onTypeFormatting.retire()
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(BiomeLspDocumentListener.TOPIC)
                    .serverStopped(this@BiomeLspServerDescriptor)
            }
        }
    }

    override val lspCustomization = object : LspCustomization() {
        override val goToDefinitionCustomizer = LspGoToDefinitionDisabled
        override val completionCustomizer = LspCompletionDisabled
        // Native application lacks the final lifecycle check required by the opt-in setting.
        override val onTypeFormattingCustomizer = LspOnTypeFormattingDisabled

        override val formattingCustomizer = object : LspFormattingSupport() {
            override fun shouldFormatThisFileExclusivelyByServer(
                file: VirtualFile,
                ideCanFormatThisFileItself: Boolean,
                serverExplicitlyWantsToFormatThisFile: Boolean,
            ): Boolean {
                val settings = BiomeSettings.getInstance(project)
                return settings.enableLspFormat
            }
        }

        override val diagnosticsCustomizer = object : LspDiagnosticsSupport() {
            override fun getMessage(diagnostic: Diagnostic): String {
                val code = diagnostic.code?.let { it.left ?: it.right?.toString() }
                return "Biome: ${super.getMessage(diagnostic)}" + (code?.let { " ($it)" } ?: "")
            }

            override fun getTooltip(diagnostic: Diagnostic): String {
                val code = diagnostic.code?.let { it.left ?: it.right?.toString() }
                val href = documentationLink(diagnostic)
                val tooltip = if (code != null && href != null) {
                    HtmlChunk.fragment(
                        HtmlChunk.text(StringUtil.convertLineSeparators("Biome: ${super.getMessage(diagnostic)} (")),
                        HtmlChunk.link(href, StringUtil.convertLineSeparators(code)),
                        HtmlChunk.text(")"),
                    ).toString()
                } else {
                    StringUtil.escapeXmlEntities(getMessage(diagnostic))
                }
                return tooltip.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>").replace("<br/>", "<br>")
            }

            private fun documentationLink(diagnostic: Diagnostic): String? {
                val href = diagnostic.codeDescription?.href ?: return null
                if (href.any { it.isISOControl() }) return null
                val uri = try {
                    URI(href)
                } catch (_: URISyntaxException) {
                    return null
                }
                return href.takeIf {
                    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
                        !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
                }
            }
        }
    }

    override val clientCapabilities: ClientCapabilities
        get() = super.clientCapabilities.apply {
            workspace.configuration = true
            // Save actions apply immediate edits without synchronous resolution inside a write command.
            textDocument.codeAction.resolveSupport = null
        }

    override fun getWorkspaceConfiguration(item: ConfigurationItem): BiomeLspWorkspaceSettings? {
        if (item.section != "biome") {
            return null
        }

        return BiomeLspWorkspaceSettings().apply {
            configurationPath = configPath?.takeIf { it.isNotBlank() }?.let {
                // Relative overrides belong to the server's workspace; absolute host paths need target mapping.
                if (Path.of(it).isAbsolute) targetRun.toTargetPath(it) else it
            }
        }
    }
}
