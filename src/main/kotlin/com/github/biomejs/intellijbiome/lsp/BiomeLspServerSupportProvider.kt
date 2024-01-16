package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.extensions.runBiomeCLI
import com.github.biomejs.intellijbiome.listeners.BIOME_CONFIG_RESOLVED_TOPIC
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.util.SmartList

@Suppress("UnstableApiUsage")
class BiomeLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter
    ) {
        val executable = BiomePackage(project).binaryPath() ?: return
        serverStarter.ensureServerStarted(LspServerDescriptor(project, executable))
    }
}

@Suppress("UnstableApiUsage")
private class LspServerDescriptor(project: Project, val executable: String) :
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
        val configPath = biomePackage.configPath
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
