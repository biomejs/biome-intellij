package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.*
import com.github.biomejs.intellijbiome.extensions.findNearestBiomeConfig
import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import kotlin.io.path.Path
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationItem


@Suppress("UnstableApiUsage") class BiomeLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        // Finds the root directory of a Biome workspace. It's typically the parent directory of `biome.json`.
        // If no `biome.json` file found, fallbacks to the project root directory.
        val projectRootDir = project.guessProjectDir() ?: return
        val root = file.findNearestBiomeConfig(projectRootDir)?.parent ?: projectRootDir

        val biome = BiomePackage(project)
        val configPath = biome.configPath()
        val executable = biome.binaryPath(root.path, file, false) ?: return
        val version = runBlocking { biome.versionNumber() }

        serverStarter.ensureServerStarted(BiomeLspServerDescriptor(project, root, executable, version, configPath))
    }

    override fun createLspServerWidgetItem(lspServer: LspServer,
        currentFile: VirtualFile?) =
        LspServerWidgetItem(lspServer, currentFile, BiomeIcons.BiomeIcon, BiomeConfigurable::class.java)
}

@Suppress("UnstableApiUsage") private class BiomeLspServerDescriptor(
    project: Project,
    root: VirtualFile,
    executable: String,
    version: String?,
    private val configPath: String?,
) : LspServerDescriptor(project, "Biome", root) {
    private val targetRun: BiomeTargetRun = run {
        var builder = BiomeTargetRunBuilder(project)
            .getBuilder(executable)
            .addParameters(listOf(ProcessCommandParameter.Value("lsp-proxy")))

        // Backward compatibility for v1; `--config-path` is no longer available in v2
        if (version != null && version.startsWith("1.") && !configPath.isNullOrEmpty()) {
            builder = builder.addParameters(listOf(
                ProcessCommandParameter.Value("--config-path"),
                ProcessCommandParameter.FilePath(Path(configPath))
            ))
        }

        builder.build()
    }

    override fun isSupportedFile(file: VirtualFile): Boolean {
        return BiomeSettings.getInstance(project).fileSupported(file)
            && roots.any { root -> file.toNioPath().startsWith(root.toNioPath()) }
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
