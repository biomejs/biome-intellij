package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.extensions.isSuccess
import com.github.biomejs.intellijbiome.extensions.runBiomeCLI
import com.github.biomejs.intellijbiome.extensions.runProcessFuture
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.SmartList
import com.jetbrains.rd.util.EnumSet
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

class BiomeStdinRunner(private val project: Project) : BiomeRunner {
    private val biomePackage = BiomePackage(project)

    override fun check(request: BiomeRunner.Request, features: EnumSet<Feature>): BiomeRunner.Response {
        val commandLine = createCommandLine(request.virtualFile, "check", getCheckFlags(features))
        val file = request.virtualFile
        val timeout = request.timeout
        val failureMessage = BiomeBundle.message(
            "biome.failed.to.run.biome.check.with.features",
            features.joinToString(prefix = "(", postfix = ")") { it -> it.toString().lowercase() },
            file.name
        )
        val future = startTheFuture(
            failureMessage, timeout
        )

        commandLine.runProcessFuture().thenAccept { result ->
            if (result.processEvent.isSuccess) {
                future.complete(BiomeRunner.Response.Success(result.processOutput.stdout))
            } else {
                future.complete(
                    BiomeRunner.Response.Failure(
                        failureMessage,
                        result.processOutput.stderr, result.processEvent.exitCode
                    )
                )
            }
        }

        return ProgressIndicatorUtils.awaitWithCheckCanceled(future)
    }

    override fun createCommandLine(file: VirtualFile, action: String, args: List<String>): GeneralCommandLine {
        val configPath = biomePackage.configPath
        val exePath = biomePackage.binaryPath()
        val params = SmartList(action, "--stdin-file-path", file.path)
        params.addAll(args)

        if (!configPath.isNullOrEmpty()) {
            params.add("--config-path")
            params.add(configPath)
        }

        if (exePath.isNullOrEmpty()) {
            throw ExecutionException(BiomeBundle.message("biome.language.server.not.found"))
        }

        return GeneralCommandLine().runBiomeCLI(project, exePath).apply {
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

    private fun getCheckFlags(features: EnumSet<Feature>): List<String> {
        val args = SmartList<String>()

        if (features.contains(Feature.Format)) {
            args.add("--formatter-enabled=true")
        } else {
            args.add("--formatter-enabled=false")
        }

        if (features.contains(Feature.SafeFixes) && !features.contains(Feature.UnsafeFixes)) {
            args.add("--apply")
        }

        if (features.contains(Feature.UnsafeFixes)) {
            args.add("--apply-unsafe")
        }

        return args
    }
}
