@file:Suppress("UnstableApiUsage")

package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.*
import com.github.biomejs.intellijbiome.extensions.findBiomeConfigs
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationItem
import kotlin.io.path.Path


class BiomeLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        val servers =
            LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
        val roots = if (servers.isEmpty()) {
            // it seems that the server is not started yet, so we need to find the root directories
            project.findBiomeConfigs().map { it.parent }.distinct()
        } else {
            servers.flatMap { it.descriptor.roots.toList() }.distinct().toMutableList().apply {
                // If the server is already running, add the new root directory to the list and stop the server.
                if (contains(file)) {
                    add(file)
                    BiomeServerService.getInstance(project).stopBiomeServer()
                }
            }
        }

        val biome = BiomePackage(project)
        val configPath = biome.configPath()

        // Finds the Biome executable and check the version using CLI.
        val executable = biome.binaryPath(file) ?: return
        val version = runBlocking { biome.versionNumber() }
        val descriptor = BiomeLspServerDescriptor(project, executable, version, configPath, roots.toTypedArray())
        serverStarter.ensureServerStarted(descriptor)
    }

    override fun createLspServerWidgetItem(
        lspServer: LspServer,
        currentFile: VirtualFile?
    ): LspServerWidgetItem {
        return LspServerWidgetItem(lspServer, currentFile, BiomeIcons.BiomeIcon, BiomeConfigurable::class.java)
    }
}

internal class BiomeLspServerDescriptor(
    project: Project,
    executable: String,
    version: String?,
    private val configPath: String?,
    roots: Array<VirtualFile>,
) : LspServerDescriptor(project, "Biome", *roots) {
    private val targetRun: BiomeTargetRun = run {
        var builder = BiomeTargetRunBuilder(project).getBuilder(executable)
            .addParameters(listOf(ProcessCommandParameter.Value("lsp-proxy")))

        // Backward compatibility for v1; `--config-path` is no longer available in v2
        if (version != null && version.startsWith("1.") && !configPath.isNullOrEmpty()) {
            builder = builder.addParameters(listOf(ProcessCommandParameter.Value("--config-path"),
                ProcessCommandParameter.FilePath(Path(configPath))))
        }

        builder.build()
    }

    override fun isSupportedFile(file: VirtualFile): Boolean {
        return BiomeSettings.getInstance(project).fileSupported(file) && roots.any { root ->
            file.toNioPath().startsWith(root.toNioPath())
        }
    }

    override fun createCommandLine(): GeneralCommandLine {
        throw RuntimeException("Not expected to be called because startServerProcess() is overridden")
    }

    override fun startServerProcess(): OSProcessHandler = targetRun.startProcess()

    override fun getFilePath(file: VirtualFile): String = targetRun.toTargetPath(file.path)

    override fun findLocalFileByPath(path: String): VirtualFile? =
        super.findLocalFileByPath(targetRun.toLocalPath(path))

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

    override val clientCapabilities: ClientCapabilities
        get() = super.clientCapabilities.apply {
            workspace.configuration = true
        }

    override fun getWorkspaceConfiguration(item: ConfigurationItem): BiomeLspWorkspaceSettings? {
        if (item.section != "biome") {
            return null
        }

        return BiomeLspWorkspaceSettings().apply {
            configurationPath = configPath
        }
    }
}
