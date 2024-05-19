package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomeIcons
import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.extensions.runBiomeCLI
import com.github.biomejs.intellijbiome.listeners.BIOME_CONFIG_RESOLVED_TOPIC
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.*
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import com.intellij.util.SmartList


@Suppress("UnstableApiUsage")
class BiomeLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter
    ) {
        val currentConfigPath = project.service<BiomeServerService>().getCurrentConfigPath()
        if (currentConfigPath != null) {
            val executable = BiomePackage(project).binaryPath(currentConfigPath, false) ?: return
            serverStarter.ensureServerStarted(BiomeLspServerDescriptor(project, executable, currentConfigPath))
            return
        }

        val configPath = BiomePackage(project).configPath(file)
        val executable = BiomePackage(project).binaryPath(configPath, false) ?: return
        serverStarter.ensureServerStarted(BiomeLspServerDescriptor(project, executable, configPath))
    }

    override fun createLspServerWidgetItem(lspServer: LspServer, currentFile: VirtualFile?) = LspServerWidgetItem(
        lspServer, currentFile,
        BiomeIcons.BiomeIcon, BiomeConfigurable::class.java
    )
}

@Suppress("UnstableApiUsage")
class BiomeLspServerManagerListener(val project: Project) : LspServerManagerListener {
    override fun serverStateChanged(lspServer: LspServer) {
        if (lspServer.descriptor is BiomeLspServerDescriptor && lspServer.state == LspServerState.ShutdownUnexpectedly) {
            // restart again if the server was shutdown unexpectedly.
            // This can be caused by race condition, when we restart LSP server because of config change,
            // but Intellij also tried to send a request to it at the same time.
            // Unfortunate There is no way prevent IDEA send requests after LSP started.
            project.service<BiomeServerService>().restartBiomeServer()
        }
    }
}

@Suppress("UnstableApiUsage")
private class BiomeLspServerDescriptor(project: Project, val executable: String, val configPath: String?) :
    ProjectWideLspServerDescriptor(project, "Biome") {
    private val biomePackage = BiomePackage(project)

    override fun isSupportedFile(file: VirtualFile): Boolean {
        val settings = BiomeSettings.getInstance(project)
        if (!settings.isEnabled()) {
            return false
        }

        return BiomeSettings.getInstance(project).fileSupported(project, file)
    }

    override fun createCommandLine(): GeneralCommandLine {
        val params = SmartList("lsp-proxy")

        if (!configPath.isNullOrEmpty()) {
            params.add("--config-path")
            params.add(configPath)
        }

        if (executable.isEmpty()) {
            throw ExecutionException(BiomeBundle.message("biome.language.server.not.found"))
        }

        val version = biomePackage.versionNumber()

        version?.let { project.messageBus.syncPublisher(BIOME_CONFIG_RESOLVED_TOPIC).resolved(it) }

        return GeneralCommandLine().runBiomeCLI(project, executable).apply {
            addParameters(params)
            withWorkDirectory(configPath)
        }
    }

    override val lspGoToDefinitionSupport = false
    override val lspCompletionSupport = null

    override val lspFormattingSupport = object : LspFormattingSupport() {
        override fun shouldFormatThisFileExclusivelyByServer(
            file: VirtualFile,
            ideCanFormatThisFileItself: Boolean,
            serverExplicitlyWantsToFormatThisFile: Boolean
        ): Boolean {
            return BiomeSettings.getInstance(project).fileSupported(project, file)
        }
    }
}
