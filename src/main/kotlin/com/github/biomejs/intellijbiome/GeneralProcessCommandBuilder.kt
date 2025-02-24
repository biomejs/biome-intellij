package com.github.biomejs.intellijbiome

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.Charset
import kotlin.io.path.Path

class GeneralProcessCommandBuilder : ProcessCommandBuilder {
    private val command =
        GeneralCommandLine().withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
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
        // TODO: custom executable is not supported in WSL yet
        parameters.addAll(params.map { it.toString() })
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

        command.withExePath(exec)
        workingDir?.let { command.withWorkingDirectory(Path(it)) }
        inputFile?.let { command.withInput(File(it.path)) }
        command.addParameters(parameters)
        charset?.let { command.withCharset(it) }

        return BiomeTargetRun.General(command)
    }
}
