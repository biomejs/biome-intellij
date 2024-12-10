package com.github.biomejs.intellijbiome

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.target.value.TargetValue
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.openapi.project.Project
import com.intellij.javascript.nodejs.execution.NodeTargetRunOptions.Companion.of
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreter
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.Charset

class NodeProcessCommandBuilder(
    project: Project,
    interpreter: NodeJsInterpreter,
) : ProcessCommandBuilder {

    private val target = NodeTargetRun(interpreter, project, null, of(false))
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

    override fun addParameters(params: List<String>): ProcessCommandBuilder {
        parameters.addAll(params)
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

    override fun build(): OSProcessHandler {
        val exec = executable ?: throw ExecutionException(BiomeBundle.message("biome.language.server.not.found"))

        workingDir?.let { builder.setWorkingDirectory(target.path(it)) }
        builder.addParameter(target.path(exec))
        parameters.forEach { builder.addParameter(it) }
        inputFile?.let { builder.setInputFile(TargetValue.fixed(it.path)) }
        charset?.let { builder.charset = it }

        val process = target.startProcessEx()
        return process.processHandler
    }
}
