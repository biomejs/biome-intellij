package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.*
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import com.intellij.util.SmartList
import kotlin.io.path.Path


@Suppress("UnstableApiUsage") class BiomeLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        val currentConfigPath = project.service<BiomeServerService>().getCurrentConfigPath()
        if (currentConfigPath != null) {
            val executable = BiomePackage(project).binaryPath(currentConfigPath, file, false) ?: return
            serverStarter.ensureServerStarted(BiomeLspServerDescriptor(project, executable, currentConfigPath))
            return
        }

        val configPath = BiomePackage(project).configPath(file)
        val executable = BiomePackage(project).binaryPath(configPath, file, false) ?: return
        serverStarter.ensureServerStarted(BiomeLspServerDescriptor(project, executable, configPath))
    }

    override fun createLspServerWidgetItem(lspServer: LspServer,
        currentFile: VirtualFile?) =
        LspServerWidgetItem(lspServer, currentFile, BiomeIcons.BiomeIcon, BiomeConfigurable::class.java)
}

@Suppress("UnstableApiUsage") private class BiomeLspServerDescriptor(project: Project,
    executable: String,
    configPath: String?) : ProjectWideLspServerDescriptor(project, "Biome") {
    private val targetRun = run {
        val params = SmartList<ProcessCommandParameter>(ProcessCommandParameter.Value("lsp-proxy"))
        if (!configPath.isNullOrEmpty()) {
            params.add(ProcessCommandParameter.Value("--config-path"))
            params.add(ProcessCommandParameter.FilePath(Path(configPath)))
        }

        BiomeTargetRunBuilder(project).getBuilder(executable).apply {
            if (configPath.isNullOrEmpty()) {
                setWorkingDirectory(configPath)
            }
            addParameters(params)
        }.build()
    }

    override fun isSupportedFile(file: VirtualFile): Boolean {
        return BiomeSettings.getInstance(project).fileSupported(file)
    }

    override fun createCommandLine(): GeneralCommandLine {
        throw RuntimeException("Not expected to be called because startServerProcess() is overridden")
    }

    override fun startServerProcess(): OSProcessHandler =
        targetRun.startProcess()

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
}
