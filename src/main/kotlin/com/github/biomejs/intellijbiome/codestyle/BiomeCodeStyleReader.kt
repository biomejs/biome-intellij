package com.github.biomejs.intellijbiome.codestyle

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.BiomeTargetRunBuilder
import com.github.biomejs.intellijbiome.ProcessCommandParameter
import com.github.biomejs.intellijbiome.extensions.findNearestBiomeConfig
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal suspend fun readBiomeCodeStyle(project: Project, file: VirtualFile): BiomeCodeStylePreferences =
    withContext(Dispatchers.IO) {
        val run = readAction {
            val root = project.getBaseDirectories().firstOrNull { VfsUtilCore.isAncestor(it, file, true) }
                ?: throw ExecutionException(BiomeBundle.message("biome.import.code.style.unavailable"))
            val biome = BiomePackage(project)
            val executable = biome.binaryPath(file)
                ?: throw ExecutionException(BiomeBundle.message("biome.import.code.style.unavailable"))
            val configPath = biome.configPath()?.takeIf { it.isNotBlank() }
            val workingDirectory = if (configPath == null) file.findNearestBiomeConfig(root)?.parent ?: root else root
            // Biome canonicalizes its working directory. Matching that spelling keeps nested
            // configuration lookup correct through aliases such as macOS /var -> /private/var.
            val workingFilePath = workingDirectory.toNioPath()
            val workingPath = workingFilePath.toRealPath()
            // Preserve the selected filename and symlink components used by per-file overrides.
            val selectedPath = workingPath.resolve(workingFilePath.relativize(file.toNioPath()))
            val arguments = mutableListOf<ProcessCommandParameter>(
                ProcessCommandParameter.Value("format"),
                ProcessCommandParameter.Value("--stdin-file-path"),
                ProcessCommandParameter.FilePath(selectedPath),
                ProcessCommandParameter.Value("--colors=off"),
            )
            if (configPath != null) {
                arguments += ProcessCommandParameter.Value("--config-path")
                arguments += biomeCodeStyleConfigArgument(configPath)
            }
            BiomeTargetRunBuilder(project).getBuilder(executable, workingPath.toString())
                .addParameters(arguments).build()
        }
        val output = runBiomeCodeStyleProbe(run)
        if (output.exitCode != 0) {
            throw ExecutionException(BiomeBundle.message("biome.import.code.style.process.failed",
                output.exitCode, output.stderr.trim().take(1_000)))
        }
        decodeBiomeCodeStyle(output.stdout)
            ?: throw ExecutionException(BiomeBundle.message("biome.import.code.style.unexpected.output"))
    }

/** Match LSP configuration-path handling, including target-native paths on Windows. */
internal fun biomeCodeStyleConfigArgument(configPath: String): ProcessCommandParameter {
    val path = Path.of(configPath)
    return if (path.isAbsolute) ProcessCommandParameter.FilePath(path) else ProcessCommandParameter.Value(configPath)
}

internal data class BiomeCodeStyleOutput(val exitCode: Int, val stdout: String, val stderr: String)

/** A single CLI invocation owns its process and never starts or connects to a Biome daemon. */
internal suspend fun runBiomeCodeStyleProbe(
    run: BiomeTargetRun,
    timeoutMillis: Long = 10_000,
): BiomeCodeStyleOutput = withTimeoutOrNull(timeoutMillis) {
    withContext(Dispatchers.IO) {
        val handler = run.startProcess()
        handler.setShouldDestroyProcessRecursively(true)
        try {
            val completion = CompletableDeferred<BiomeCodeStyleOutput>()
            val stdout = StringBuffer()
            val stderr = StringBuffer()
            val oversized = AtomicBoolean()
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val buffer = when (outputType) {
                        ProcessOutputTypes.STDOUT -> stdout
                        ProcessOutputTypes.STDERR -> stderr
                        else -> return
                    }
                    synchronized(buffer) {
                        if (buffer.length + event.text.length > 65_536) {
                            oversized.set(true)
                            handler.destroyProcess()
                        } else buffer.append(event.text)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    completion.complete(BiomeCodeStyleOutput(event.exitCode, stdout.toString(), stderr.toString()))
                }
            })
            if (!handler.isStartNotified) handler.startNotify()
            currentCoroutineContext().ensureActive()
            handler.processInput?.use { it.write(BIOME_CODE_STYLE_PROBE.toByteArray(Charsets.UTF_8)) }
                ?: throw ExecutionException(BiomeBundle.message("biome.import.code.style.unexpected.output"))
            val result = completion.await()
            if (oversized.get()) throw ExecutionException(BiomeBundle.message("biome.import.code.style.output.too.large"))
            result
        } finally {
            if (!handler.isProcessTerminated) {
                handler.destroyProcess()
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!handler.waitFor(1_000)) {
                        handler.process.destroyForcibly()
                        handler.waitFor(1_000)
                    }
                }
            }
        }
    }
} ?: throw ExecutionException(BiomeBundle.message("biome.import.code.style.timeout"))
