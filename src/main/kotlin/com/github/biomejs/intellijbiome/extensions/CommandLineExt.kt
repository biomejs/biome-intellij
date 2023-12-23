package com.github.biomejs.intellijbiome.extensions

import com.github.biomejs.intellijbiome.BiomeBundle
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutput
import com.intellij.javascript.nodejs.interpreter.NodeCommandLineConfigurator
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

class ProcessResult(val processEvent: ProcessEvent, val processOutput: ProcessOutput)

val ProcessEvent.isSuccess: Boolean get() = exitCode == 0

fun GeneralCommandLine.runProcessFuture(): CompletableFuture<ProcessResult> {
    val future = CompletableFuture<ProcessResult>()

    val processHandler = CapturingProcessHandler(this.withCharset(StandardCharsets.UTF_8))

    processHandler.addProcessListener(object : CapturingProcessAdapter() {
        override fun processTerminated(event: ProcessEvent) {
            future.complete(ProcessResult(event, output))
        }
    })

    processHandler.startNotify()

    return future
}

fun GeneralCommandLine.runWithNodeInterpreter(project: Project, command: String): GeneralCommandLine {
    val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
    if (interpreter !is NodeJsLocalInterpreter && interpreter !is WslNodeInterpreter) {
        throw ExecutionException(BiomeBundle.message("biome.interpreter.not.configured"))
    }

    return this.apply {
        withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        withWorkDirectory(project.basePath)
        addParameter(command)
        NodeCommandLineConfigurator.find(interpreter)
            .configure(this, NodeCommandLineConfigurator.defaultOptions(project))
    }
}

fun GeneralCommandLine.runCommand(project: Project, executablePath: String): GeneralCommandLine {
    return this.apply {
        withExePath(executablePath)
        withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        withWorkDirectory(project.basePath)
    }
}

fun GeneralCommandLine.runBiomeCLI(project: Project, command: String): GeneralCommandLine {
    val executableFile = File(command)

    return if (executableFile.isFile && executableFile.isNodeScript()) {
        this.runWithNodeInterpreter(project, command)
    } else {
        this.runCommand(project, command)
    }
}



