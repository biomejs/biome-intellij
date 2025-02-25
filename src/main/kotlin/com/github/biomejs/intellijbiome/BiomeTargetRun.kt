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
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.util.io.BaseOutputReader
import kotlin.io.path.Path

/**
 * Wraps a function that starts a process to be run with an empty progress indicator as a workaround.
 * Starting process will throw a `java.lang.IllegalStateException` on some cases on WSL 2 environment.
 * This is a IntelliJ's bug and already fixed, but it's not shipped in WebStorm yet.
 *
 * TODO(siketyan): Remove this after bumped to >= 2025.1 (see IDEA-361222)
 *
 * @see <a href="https://youtrack.jetbrains.com/issue/IDEA-347138/">IDEA-347138</a>
 * @see <a href="https://youtrack.jetbrains.com/issue/IDEA-361122/">IDEA-361122</a>
 * @see <a href="https://github.com/JetBrains/intellij-community/commit/4128257db806af35e52556ac16bbac8776019544">Commit 4128257 in intellij-community</a>
 */
fun wrapStartProcess(processCreator: () -> OSProcessHandler): OSProcessHandler =
    ProgressManager.getInstance().runProcess(Computable(processCreator), EmptyProgressIndicator())

sealed interface BiomeTargetRun {
    fun startProcess(): OSProcessHandler
    fun toTargetPath(path: String): String
    fun toLocalPath(path: String): String

    class Node(private val run: NodeTargetRun) : BiomeTargetRun {
        override fun startProcess(): OSProcessHandler =
            wrapStartProcess {
                run.startProcessEx().processHandler
            }

        override fun toTargetPath(path: String) = run.convertLocalPathToTargetPath(path)
        override fun toLocalPath(path: String) = run.convertTargetPathToLocalPath(path)
    }

    class General(
        private val command: GeneralCommandLine,
        private val wslDistribution: WSLDistribution? = null,
    ) : BiomeTargetRun {
        override fun startProcess(): OSProcessHandler =
            wrapStartProcess {
                object : CapturingProcessHandler(command) {
                    override fun readerOptions(): BaseOutputReader.Options {
                        return object : BaseOutputReader.Options() {
                            // This option ensures that line separators are not converted to LF
                            // when the formatter sends e.g., CRLF
                            override fun splitToLines(): Boolean = false
                        }
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
