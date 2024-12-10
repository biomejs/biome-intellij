package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.extensions.isSuccess
import com.github.biomejs.intellijbiome.extensions.runProcessFuture
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import com.intellij.util.SmartList
import kotlinx.coroutines.future.await
import java.util.EnumSet

class BiomeStdinRunner(project: Project) : BiomeRunner {
    private val biomePackage = BiomePackage(project)
    private val settings = BiomeSettings.getInstance(project)
    private val features = settings.getEnabledFeatures()
    private val targetRunBuilder = BiomeTargetRunBuilder(project)

    override suspend fun check(request: BiomeRunner.Request): BiomeRunner.Response {
        val file = request.virtualFile
        val configPath = biomePackage.configPath(file)
        val exePath = biomePackage.binaryPath(configPath, false)
            ?: throw ExecutionException(BiomeBundle.message("biome.language.server.not.found"))

        val params = SmartList("check", "--stdin-file-path", file.path)
        if (!configPath.isNullOrEmpty()) {
            params.add("--config-path")
            params.add(configPath)
        }
        params.addAll(getCheckFlags(features, biomePackage))

        val processHandler = targetRunBuilder.getBuilder(exePath).apply {
            if (!configPath.isNullOrEmpty()) {
                setWorkingDirectory(configPath)
            }
            addParameters(params)
            setInputFile(file)
        }.build()

        val result = processHandler.runProcessFuture().await()

        val processOutput = result.processOutput
        val stdout = processOutput.stdout.trim()
        val stderr = processOutput.stderr.trim()

        if (result.processEvent.isSuccess) {
            return BiomeRunner.Response.Success(stdout)
        } else {
            if (processOutput.isTimeout) {
                return BiomeRunner.Response.Failure(BiomeBundle.message("biome.failed.to.run.biome.check.with.features",
                    features.joinToString(prefix = "(", postfix = ")") { it -> it.toString().lowercase() },
                    file.name), "Timeout exceeded", null)
            }

            return BiomeRunner.Response.Failure(BiomeBundle.message("biome.failed.to.run.biome.check.with.features",
                features.joinToString(prefix = "(", postfix = ")") { it -> it.toString().lowercase() },
                file.name), stderr, processOutput.exitCode)
        }
    }

    suspend fun getCheckFlags(features: EnumSet<Feature>,
        biomePackage: BiomePackage): List<String> {
        val args = SmartList<String>()

        if (features.isEmpty()) return args

        if (features.contains(Feature.Format)) {
            args.add("--formatter-enabled=true")
        } else {
            args.add("--formatter-enabled=false")
        }

        if (!features.contains(Feature.SafeFixes) && !features.contains(Feature.UnsafeFixes)) {
            args.add("--linter-enabled=false")
        }

        val version = biomePackage.versionNumber()
        if (version === null || version.isEmpty() || biomePackage.compareVersion(version, "1.8.0") >= 0) {
            args.add("--write")
            if (features.contains(Feature.UnsafeFixes)) {
                args.add("--unsafe")
            }
        } else {
            if (features.contains(Feature.UnsafeFixes)) {
                args.add("--apply-unsafe")
            } else {
                args.add("--apply")
            }
        }

        args.add("--skip-errors")

        return args
    }
}
