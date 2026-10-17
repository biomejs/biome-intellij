package com.github.biomejs.intellijbiome

import com.intellij.execution.ExecutionException
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.target.TargetEnvironment
import com.intellij.execution.target.TargetedCommandLine
import com.intellij.execution.target.TargetedCommandLineBuilder
import com.intellij.execution.target.value.TargetValue
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.NodeTargetRunOptions.Companion.of
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreter
import com.intellij.javascript.nodejs.interpreter.NodeTargetRunSetup
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.BaseDataReader
import com.intellij.util.io.BaseOutputReader
import java.nio.charset.Charset

class NodeProcessCommandBuilder(
    project: Project,
    private val interpreter: NodeJsInterpreter,
) : ProcessCommandBuilder {

    private var localLspProxy = false
    private val options = of(false, null) {
        if (interpreter is NodeJsLocalInterpreter) {
            // A local proxy's daemon may retain its pipes after the proxy exits. Let the
            // readers finish without killing that shared daemon or changing CRLF output.
            it.processReaderOptions = object : BaseOutputReader.Options() {
                override fun policy() = BaseDataReader.SleepingPolicy.NON_BLOCKING
                override fun splitToLines() = false
            }
        }
    }
    private val target = run {
        val delegate = interpreter.createTargetRunSetup(project, options)
        val setup = if (interpreter is NodeJsLocalInterpreter) object : NodeTargetRunSetup(delegate.request, delegate.pathMapper) {
            override fun configureCommandLine(targetRun: NodeTargetRun) = delegate.configureCommandLine(targetRun)

            override fun configureEnvironment(builder: TargetedCommandLineBuilder, data: EnvironmentVariablesData) =
                delegate.configureEnvironment(builder, data)

            override fun prependNodeDirToPATH(builder: TargetedCommandLineBuilder, data: EnvironmentVariablesData) =
                delegate.prependNodeDirToPATH(builder, data)

            override fun createProcessHandler(run: NodeTargetRun, process: Process,
                commandLine: TargetedCommandLine, environment: TargetEnvironment) =
                delegate.createProcessHandler(run,
                    if (localLspProxy) BiomeLspProxyProcess(process) else process, commandLine, environment)
        } else delegate
        NodeTargetRun(interpreter, project, null, options, setup)
    }
    private val builder = target.commandLineBuilder
    private var executable: String? = null
    private var workingDir: String? = null
    private var inputFile: VirtualFile? = null
    private var charset: Charset? = null
    private val parameters = mutableListOf<String>()

    override fun setWorkingDirectory(path: String?): ProcessCommandBuilder {
        workingDir = path
        return this
    }

    override fun setInputFile(file: VirtualFile?): ProcessCommandBuilder {
        inputFile = file
        return this
    }

    override fun addParameters(params: List<ProcessCommandParameter>): ProcessCommandBuilder {
        parameters.addAll(params.map {
            when (it) {
                is ProcessCommandParameter.Value -> it.value
                is ProcessCommandParameter.FilePath -> target.convertLocalPathToTargetPath(it.path.toString())
            }
        })
        return this
    }

    override fun setExecutable(executable: String): ProcessCommandBuilder {
        this.executable = executable
        return this
    }

    override fun setCharset(charset: Charset): ProcessCommandBuilder {
        this.charset = charset
        return this
    }

    override fun build(): BiomeTargetRun {
        val exec = executable ?: throw ExecutionException(BiomeBundle.message("biome.language.server.not.found"))
        localLspProxy = interpreter is NodeJsLocalInterpreter &&
            !interpreter.interpreterSystemIndependentPath.replace('\\', '/').startsWith("//") &&
            isLocalBiomeLspProxy(parameters, exec, workingDir)

        workingDir?.let { builder.setWorkingDirectory(target.path(it)) }
        builder.addParameter(target.path(exec))
        parameters.forEach { builder.addParameter(it) }
        inputFile?.let { builder.setInputFile(TargetValue.fixed(it.path)) }
        charset?.let { builder.charset = it }

        return BiomeTargetRun.Node(target)
    }
}
