package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.extensions.isSuccess
import com.github.biomejs.intellijbiome.extensions.runProcessFuture
import com.github.biomejs.intellijbiome.extensions.runWithNodeInterpreter
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.SmartList
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

class BiomeStdinRunner(private val project: Project) : BiomeRunner {
    override fun format(request: BiomeRunner.Request): BiomeRunner.Response {
        val commandLine = createCommandLine(request.virtualFile, "format")
        val file = request.virtualFile
        val timeout = request.timeout
        val future = startTheFuture(
            BiomeBundle.message(
                "biome.failed.to.format.file",
                file.name
            ), timeout
        )

        commandLine.runProcessFuture().thenAccept { result ->
            if (result.processEvent.isSuccess) {
                future.complete(BiomeRunner.Response.Success(result.processOutput.stdout))
            } else {
                future.complete(
                    BiomeRunner.Response.Failure(
                        BiomeBundle.message("biome.failed.to.format.file", file.name),
                        result.processOutput.stderr, result.processEvent.exitCode
                    )
                )
            }
        }

        return ProgressIndicatorUtils.awaitWithCheckCanceled(future)
    }

    override fun applySafeFixes(request: BiomeRunner.Request): BiomeRunner.Response {
        val commandLine = createCommandLine(request.virtualFile, "lint", "--apply")
        return runFixCommand(request, commandLine)
    }

    override fun applyUnsafeFixes(request: BiomeRunner.Request): BiomeRunner.Response {
        val commandLine = createCommandLine(request.virtualFile, "lint", "--apply-unsafe")
        return runFixCommand(request, commandLine)
    }

    private fun runFixCommand(
        request: BiomeRunner.Request,
        commandLine: GeneralCommandLine
    ): BiomeRunner.Response {
        val file = request.virtualFile
        val timeout = request.timeout
        val future = startTheFuture(
            BiomeBundle.message(
                "biome.failed.to.fix.file",
                file.name
            ), timeout
        )

        commandLine.runProcessFuture().thenAccept { result ->
            if (result.processEvent.isSuccess) {
                future.complete(BiomeRunner.Response.Success(result.processOutput.stdout))
            } else {
                future.complete(
                    BiomeRunner.Response.Failure(
                        BiomeBundle.message("biome.failed.to.fix.file", file.name),
                        result.processOutput.stderr, result.processEvent.exitCode
                    )
                )
            }
        }

        return ProgressIndicatorUtils.awaitWithCheckCanceled(future)
    }

    override fun createCommandLine(file: VirtualFile, action: String, args: String?): GeneralCommandLine {
        val configPath = BiomePackage.configPath(project)
        val exePath = BiomePackage.binaryPath(project)
        val params = SmartList(action, "--stdin-file-path", file.path)

        if (!args.isNullOrEmpty()) {
            params.add(args)
        }

        if (!configPath.isNullOrEmpty()) {
            params.add("--config-path")
            params.add(configPath)
        }

        if (exePath.isNullOrEmpty()) {
            throw ExecutionException(BiomeBundle.message("biome.language.server.not.found"))
        }

        return GeneralCommandLine().runWithNodeInterpreter(project, exePath).apply {
            withInput(File(file.path))
            addParameters(params)
        }
    }

    private fun startTheFuture(
        timeoutMessage: String,
        timeout: Duration
    ): CompletableFuture<BiomeRunner.Response> {
        val future = CompletableFuture<BiomeRunner.Response>()
            .completeOnTimeout(
                BiomeRunner.Response.Failure(
                    timeoutMessage, "Timeout exceeded", null
                ),
                timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS
            )
        return future
    }

}
