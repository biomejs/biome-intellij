package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.*
import com.intellij.openapi.util.text.StringUtil
import com.github.biomejs.intellijbiome.extensions.findNearestBiomeConfig
import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.Diagnostic
import java.io.IOException
import java.util.concurrent.TimeUnit


class BiomeLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (project.isDisposed) return
        val settings = BiomeSettings.getInstance(project)
        if (!settings.isEnabled() || !settings.fileSupported(file)) return
        val biome = BiomePackage(project)
        val configPath = biome.configPath()

        // Finds the root directory of a Biome workspace. It's typically the parent directory of `biome.json`.
        // If no `biome.json` file found, nothing to do.
        val projectRootDir = project
            .getBaseDirectories()
            .find { VfsUtil.isUnder(file, setOf(it)) } ?: return

        val root = if (configPath.isNullOrEmpty()) {
            file.findNearestBiomeConfig(projectRootDir)?.parent ?: return
        } else {
            // When using manual configuration, the root directory will be the project root.
            projectRootDir
        }

        // Select the executable before SDK startup.
        val executable = biome.binaryPath(file) ?: return
        serverStarter.ensureServerStarted(BiomeLspServerDescriptor(project, root, executable, configPath))
    }

    override fun createLspServerWidgetItem(lspServer: LspServer,
        currentFile: VirtualFile?) =
        LspServerWidgetItem(lspServer, currentFile, BiomeIcons.BiomeIcon, BiomeConfigurable::class.java)
}

private class BiomeLspServerDescriptor(
    project: Project,
    root: VirtualFile,
    executable: String,
    private val configPath: String?,
) : LspServerDescriptor(project, "Biome", root) {
    // NodeTargetRun captures its construction-time progress indicator. A cancelled
    // discovery read must not cancel a later launch, which has its own lifecycle fence.
    private val targetRun: BiomeTargetRun = ProgressManager.getInstance().runProcess(Computable {
        BiomeTargetRunBuilder(project)
            .getBuilder(executable, root.path)
            .addParameters(listOf(ProcessCommandParameter.Value("lsp-proxy")))
            .build()
    }, EmptyProgressIndicator())

    override fun isSupportedFile(file: VirtualFile): Boolean {
        return BiomeSettings.getInstance(project).fileSupported(file)
            && roots.any { root -> file.toNioPath().startsWith(root.toNioPath()) }
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
        val handler = targetRun.startProcess()
        var handedOff = false
        try {
            // Startup is queued before the SDK publishes the server in its write action.
            // Wait for that publication, then reject a process created after its server stopped.
            val active = ApplicationManager.getApplication().runReadAction(Computable {
                !project.isDisposed && LspServerManager.getInstance(project)
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

    override val lspGoToDefinitionSupport = false
    override val lspCompletionSupport = null

    override val lspFormattingSupport = object : LspFormattingSupport() {
        override fun shouldFormatThisFileExclusivelyByServer(
            file: VirtualFile,
            ideCanFormatThisFileItself: Boolean,
            serverExplicitlyWantsToFormatThisFile: Boolean,
        ): Boolean {
            val settings = BiomeSettings.getInstance(project)
            return settings.enableLspFormat
        }
    }

    override val lspDiagnosticsSupport = object : LspDiagnosticsSupport() {
        override fun getMessage(diagnostic: Diagnostic): String {
            val code = diagnostic.code?.let { it.left ?: it.right?.toString() }
            return "Biome: ${super.getMessage(diagnostic)}" + (code?.let { " ($it)" } ?: "")
        }

        override fun getTooltip(diagnostic: Diagnostic) =
            StringUtil.escapeXmlEntities(getMessage(diagnostic))
                .replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>")
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
            configurationPath = configPath?.takeIf { it.isNotBlank() }
        }
    }
}
