package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.getWslPathSafe
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreter
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.lang.javascript.JavaScriptBundle
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.util.io.BaseOutputReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.io.File
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
                if (wslDistribution == null && isLocalBiomeLspProxy(command.parametersList.list,
                        command.exePath, command.workDirectory?.path)) {
                    return@wrapStartProcess BiomeLspProxyProcessHandler(command)
                }
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

        override fun toTargetPath(path: String) = wslDistribution?.getWslPathSafe(Path(path)) ?: path
        override fun toLocalPath(path: String) = wslDistribution?.getWindowsPath(path) ?: path
    }
}

class BiomeTargetRunBuilder(val project: Project) {
    fun getBuilder(
        executable: String,
        workingDirectory: String? = project.basePath?.takeIf { File(it).exists() },
    ): ProcessCommandBuilder {
        if (executable.isEmpty()) {
            throw ExecutionException(BiomeBundle.message("biome.language.server.not.found"))
        }

        val settings = BiomeSettings.getInstance(project)
        val hasManualExecutable = settings.configurationMode == ConfigurationMode.MANUAL &&
            settings.executablePath.isNotBlank()
        val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
        val manualNodeLauncher = if (hasManualExecutable) {
            nodeLauncherPath(executable, workingDirectory, interpreter)
        } else null

        val builder: ProcessCommandBuilder = if (hasManualExecutable && manualNodeLauncher == null) {
            GeneralProcessCommandBuilder()
        } else {
            if (interpreter !is NodeJsLocalInterpreter && interpreter !is WslNodeInterpreter) {
                throw ExecutionException(JavaScriptBundle.message("lsp.interpreter.error"))
            }
            NodeProcessCommandBuilder(project, interpreter)
        }

        val selectedExecutable = manualNodeLauncher ?: if (hasManualExecutable) {
            localWindowsBatchPath(executable, workingDirectory)
        } else executable
        return builder.setExecutable(selectedExecutable)
            .setWorkingDirectory(workingDirectory).setCharset(Charsets.UTF_8)
    }
}

/** Keep explicit Node launchers on the target selected by their paths. */
internal fun supportsManualNodeTarget(executable: String, interpreter: NodeJsInterpreter?): Boolean =
    when (interpreter) {
        is NodeJsLocalInterpreter -> !isUncPath(executable) &&
            !isUncPath(interpreter.interpreterSystemIndependentPath)
        is WslNodeInterpreter -> wslDistributionId(executable)
            ?.equals(interpreter.wslDistributionId, ignoreCase = true) == true
        else -> false
    }

/** Read the distribution without invoking the platform parser, which is disabled on other hosts. */
private fun wslDistributionId(path: String): String? {
    val normalized = path.replace('\\', '/')
    if (!normalized.startsWith("//")) return null
    val parts = normalized.removePrefix("//").split('/', limit = 3)
    if (parts.size != 3 || parts[1].isEmpty() || parts[2].isEmpty()) return null
    return parts[1].takeIf {
        parts[0].equals("wsl$", ignoreCase = true) || parts[0].equals("wsl.localhost", ignoreCase = true)
    }
}

// WslPath's parser is host-gated; a lexical UNC guard must not depend on the IDE's host OS.
private fun isUncPath(path: String): Boolean = path.replace('\\', '/').startsWith("//")

/** Windows resolves batch executables before applying the child's working directory. */
private fun localWindowsBatchPath(executable: String, workingDirectory: String?): String {
    if (!SystemInfoRt.isWindows || workingDirectory == null ||
        isUncPath(executable) || isUncPath(workingDirectory) ||
        !(executable.endsWith(".cmd", ignoreCase = true) || executable.endsWith(".bat", ignoreCase = true))) {
        return executable
    }
    return try {
        val selected = Path(executable)
        val directory = Path(workingDirectory)
        // Bare names retain PATH lookup; absolute and drive-relative selections retain their meaning.
        if (selected.parent == null || selected.root != null || !directory.isAbsolute) executable
        else directory.resolve(selected).toString()
    } catch (_: InvalidPathException) {
        executable
    }
}

/** Resolve explicit paths against the command directory; bare names retain PATH lookup. */
private fun nodeLauncherPath(
    executable: String,
    workingDirectory: String?,
    interpreter: NodeJsInterpreter?,
): String? {
    // Preserve the UNC boundary before a non-Windows Path can normalize its leading separators.
    if (isUncPath(executable) && interpreter !is WslNodeInterpreter) return null
    val path = try {
        val selected = Path(executable)
        if (!selected.isAbsolute && selected.parent == null) return null
        if (!selected.isAbsolute && workingDirectory != null && isUncPath(workingDirectory) &&
            interpreter !is WslNodeInterpreter) return null
        (workingDirectory?.let { Path(it).resolve(selected) } ?: selected).toAbsolutePath()
    } catch (_: InvalidPathException) {
        return null
    }
    return path.toString().takeIf { supportsManualNodeTarget(it, interpreter) && hasNodeShebang(it) }
}

/** Follow npm bin symlinks, but keep native files and wrappers with their own setup running directly. */
private fun hasNodeShebang(executable: String): Boolean {
    ProgressManager.checkCanceled()
    val firstLine = try {
        val path = Path(executable)
        if (!Files.isRegularFile(path)) return false
        val bytes = Files.newInputStream(path).use { it.readNBytes(257) }
        val newline = bytes.indexOf('\n'.code.toByte())
        // Never classify a partially read shebang that may contain further options or environment setup.
        val end = if (newline >= 0) newline else bytes.size.takeIf { it <= 256 } ?: return false
        String(bytes, 0, end, Charsets.UTF_8).trimEnd('\r')
    } catch (_: IOException) {
        return false
    } catch (_: InvalidPathException) {
        return false
    }
    if (!firstLine.startsWith("#!")) return false
    val command = firstLine.removePrefix("#!").trim(' ', '\t').split(Regex("[ \\t]+"))
    return when (command.size) {
        1 -> command[0].startsWith('/') && command[0].substringAfterLast('/') == "node"
        2 -> command[0] == "/usr/bin/env" && command[1] == "node"
        else -> false
    }
}
