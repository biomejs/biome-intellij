package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.lang.javascript.JavaScriptBundle
import com.intellij.openapi.project.Project
import com.intellij.util.io.BaseOutputReader
import kotlin.io.path.Path

sealed interface BiomeTargetRun {
    fun startProcess(): OSProcessHandler
    fun toTargetPath(path: String): String
    fun toLocalPath(path: String): String

    class Node(private val run: NodeTargetRun) : BiomeTargetRun {
        override fun startProcess(): OSProcessHandler = run.startProcessEx().processHandler
        override fun toTargetPath(path: String) = run.convertLocalPathToTargetPath(path)
        override fun toLocalPath(path: String) = run.convertTargetPathToLocalPath(path)
    }

    class General(
        private val command: GeneralCommandLine,
        private val wslDistribution: WSLDistribution? = null,
    ) : BiomeTargetRun {
        override fun startProcess(): OSProcessHandler =
            object : CapturingProcessHandler(command) {
                override fun readerOptions(): BaseOutputReader.Options {
                    return object : BaseOutputReader.Options() {
                        // This option ensures that line separators are not converted to LF
                        // when the formatter sends e.g., CRLF
                        override fun splitToLines(): Boolean = false
                    }
                }
            }

        override fun toTargetPath(path: String) = wslDistribution?.getWslPath(Path(path)) ?: path
        override fun toLocalPath(path: String) = wslDistribution?.getWindowsPath(path) ?: path
    }
}

class BiomeTargetRunBuilder(val project: Project) {
    fun getBuilder(
        executable: String,
    ): ProcessCommandBuilder {
        if (executable.isEmpty()) {
            throw ExecutionException(BiomeBundle.message("biome.language.server.not.found"))
        }

        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode

        val builder: ProcessCommandBuilder = if (configurationMode == ConfigurationMode.MANUAL) {
            GeneralProcessCommandBuilder()
        } else {
            val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
            if (interpreter !is NodeJsLocalInterpreter && interpreter !is WslNodeInterpreter) {
                throw ExecutionException(JavaScriptBundle.message("lsp.interpreter.error"))
            }
            NodeProcessCommandBuilder(project, interpreter)
        }

        return builder.setExecutable(executable).setWorkingDirectory(project.basePath).setCharset(Charsets.UTF_8)
    }
}
