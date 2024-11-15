package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomeIcons
import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.extensions.isNodeScript
import com.github.biomejs.intellijbiome.listeners.BIOME_CONFIG_RESOLVED_TOPIC
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.NodeTargetRunOptions.Companion.of
import com.intellij.javascript.nodejs.execution.withInvisibleProgress
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.lang.javascript.JavaScriptBundle
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.*
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import com.intellij.util.SmartList
import java.io.File
import kotlin.io.path.Path


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

    override fun createLspServerWidgetItem(lspServer: LspServer,
        currentFile: VirtualFile?) = LspServerWidgetItem(
        lspServer, currentFile,
        BiomeIcons.BiomeIcon, BiomeConfigurable::class.java
    )
}

@Suppress("UnstableApiUsage")
private class BiomeLspServerDescriptor(project: Project,
    val executable: String,
    val configPath: String?) :
    ProjectWideLspServerDescriptor(project, "Biome") {
    private val biomePackage = BiomePackage(project)
    private val executableFile = File(executable)
    private val params: SmartList<String> by lazy {
        val params = SmartList("lsp-proxy")
        if (!configPath.isNullOrEmpty()) {
            params.add("--config-path")
            params.add(configPath)
        }
        return@lazy params
    }

    init {
        biomePackage.versionNumber()?.let { project.messageBus.syncPublisher(BIOME_CONFIG_RESOLVED_TOPIC).resolved(it) }
    }

    override fun isSupportedFile(file: VirtualFile): Boolean {
        val settings = BiomeSettings.getInstance(project)
        if (!settings.isEnabled()) {
            return false
        }

        return BiomeSettings.getInstance(project).fileSupported(project, file)
    }

    override fun createCommandLine(): GeneralCommandLine {
        // we're here only if we specify an executable file
        return GeneralCommandLine().apply {
            withExePath(executable)
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            project.basePath?.let {
                withWorkingDirectory(Path(it))
            }
            addParameters(params)
        }

    }

    override fun startServerProcess(): OSProcessHandler {
        if (executable.isEmpty()) {
            throw ExecutionException(BiomeBundle.message("biome.language.server.not.found"))
        }

        if (!(executableFile.isFile && executableFile.isNodeScript())) {
            return super.startServerProcess()
        }

        val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
        if (interpreter !is NodeJsLocalInterpreter && interpreter !is WslNodeInterpreter) {
            throw ExecutionException(JavaScriptBundle.message("lsp.interpreter.error"))
        }

        val target = NodeTargetRun(interpreter, project, null, of(false))
        target.commandLineBuilder.apply {
            project.basePath?.let { this.setWorkingDirectory(target.path(it)) }
            addParameter(target.path(executable))
            addParameters(params)
            addParameter("--stdio")
            charset = Charsets.UTF_8
        }

        val process = withInvisibleProgress { target.startProcessEx() }

        return process.processHandler
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
