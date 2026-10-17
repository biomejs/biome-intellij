package com.github.biomejs.intellijbiome.launcher

import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.BiomeTargetRunBuilder
import com.github.biomejs.intellijbiome.ProcessCommandParameter
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutput
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

abstract class BiomeWindowsLauncherTestCase : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    protected lateinit var windowsNode: Path

    override fun setUp() {
        super.setUp()
        assertTrue("Native Windows launcher tests require a Windows JVM", SystemInfoRt.isWindows)
        windowsNode = Path.of(requiredEnvironment("BIOME_TEST_WINDOWS_NODE"))
        assertTrue("BIOME_TEST_WINDOWS_NODE must select an existing absolute node.exe",
            windowsNode.isAbsolute && Files.isRegularFile(windowsNode))
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.MANUAL
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(
            NodeJsInterpreterRef.create(NodeJsLocalInterpreter(windowsNode.toString())), testRootDisposable)
    }

    protected fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: error("Native Windows launcher lane requires $name")

    protected fun launcherRun(
        executable: String,
        directory: Path,
        parameters: List<ProcessCommandParameter> = emptyList(),
        input: VirtualFile? = null,
    ): BiomeTargetRun {
        BiomeSettings.getInstance(project).executablePath = executable
        return BiomeTargetRunBuilder(project).getBuilder(executable, directory.toString())
            .addParameters(parameters).setInputFile(input).build()
    }

    protected fun output(run: BiomeTargetRun): ProcessOutput {
        val execution = ApplicationManager.getApplication().executeOnPooledThread(Callable { captureOutput(run) })
        try {
            PlatformTestUtil.waitWithEventsDispatching("Native launcher did not finish", { execution.isDone }, 30)
            return execution.get(1, TimeUnit.SECONDS)
        } finally {
            if (!execution.isDone) execution.cancel(true)
        }
    }

    private fun captureOutput(run: BiomeTargetRun): ProcessOutput {
        val handler = run.startProcess()
        try {
            val completion = CompletableFuture<ProcessOutput>()
            handler.addProcessListener(object : CapturingProcessAdapter() {
                override fun processTerminated(event: ProcessEvent) {
                    super.processTerminated(event)
                    completion.complete(output)
                }
            })
            handler.startNotify()
            val result = completion.get(20, TimeUnit.SECONDS)
            assertEquals(result.stderr, 0, result.exitCode)
            return result
        } finally {
            if (handler.process.isAlive) handler.destroyProcess()
            if (!handler.waitFor(2_000)) {
                handler.process.destroyForcibly()
                check(handler.waitFor(2_000)) { "Native launcher child did not terminate" }
            }
        }
    }

    protected fun assertReport(
        run: BiomeTargetRun,
        directory: String,
        arguments: List<String>,
        marker: String = "",
        input: String = "",
        node: String? = null,
    ) {
        val report = Json.parseToJsonElement(output(run).stdout.trim()).jsonObject
        assertEquals(directory, report.getValue("cwd").jsonPrimitive.content)
        assertEquals(arguments, report.getValue("args").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(marker, report.getValue("marker").jsonPrimitive.content)
        assertEquals(input, report.getValue("input").jsonPrimitive.content)
        node?.let { assertEquals(it, report.getValue("node").jsonPrimitive.content) }
    }

    protected fun reportSource(readInput: Boolean = false): String = """
        const fs = require('node:fs');
        console.log(JSON.stringify({
          cwd: process.cwd(), args: process.argv.slice(2), node: process.execPath,
          marker: process.env.BIOME_WRAPPER_MARKER || '',
          input: ${if (readInput) "fs.readFileSync(0, 'utf8')" else "''"}
        }));
    """.trimIndent()
}
