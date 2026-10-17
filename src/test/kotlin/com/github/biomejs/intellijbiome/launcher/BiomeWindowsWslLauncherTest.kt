package com.github.biomejs.intellijbiome.launcher

import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.ProcessCommandParameter
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class BiomeWindowsWslLauncherTest : BiomeWindowsLauncherTestCase() {
    private lateinit var distribution: String
    private lateinit var wslNode: String
    private lateinit var root: Path
    private lateinit var linuxRoot: String

    override fun setUp() {
        super.setUp()
        distribution = requiredEnvironment("BIOME_TEST_WSL_DISTRO")
        wslNode = requiredEnvironment("BIOME_TEST_WSL_NODE")
        assertTrue("BIOME_TEST_WSL_NODE must be an absolute Linux path", wslNode.startsWith('/'))
        assertFalse("The distribution ID must not contain path separators", distribution.any { it == '/' || it == '\\' })
        root = Files.createTempDirectory(Path.of("\\\\wsl.localhost\\$distribution\\tmp"), "biome launcher ")
        linuxRoot = "/tmp/${root.fileName}"
        Disposer.register(testRootDisposable) { FileUtil.delete(root.toFile()) }
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(
            NodeJsInterpreterRef.create(WslNodeInterpreter(distribution, wslNode)))
    }

    fun testRecognizedWslNodeLauncherUsesConfiguredInterpreter() {
        val script = Files.writeString(root.resolve("selected launcher"), "#!/missing/node\n" + reportSource())
        val arguments = listOf("argument with spaces", "file&name.js")
        val run = launcherRun(script.toString(), root, arguments.map { ProcessCommandParameter.Value(it) })

        assertTrue("A recognized launcher in the configured distribution must use Node", run is BiomeTargetRun.Node)
        assertReport(run, linuxRoot, arguments, node = wslNode)
    }

    fun testNpmStyleNodeLauncherSupportsBothWslUncAliases() {
        val script = Files.writeString(root.resolve("npm launcher"), "#!/usr/bin/env node\n" + reportSource())
        for (prefix in listOf("\\\\wsl$\\", "\\\\wsl.localhost\\")) {
            val selected = prefix + distribution + "\\tmp\\" + root.fileName + "\\" + script.fileName
            val run = launcherRun(selected, root)
            assertTrue("The WSL UNC alias must not change Node dispatch: $prefix", run is BiomeTargetRun.Node)
            assertReport(run, linuxRoot, emptyList(), node = wslNode)
        }
    }

    fun testRelativeWslNodeLauncherUsesSelectedWorkingDirectory() {
        Files.writeString(root.resolve("selected launcher"), "#!/missing/node\n" + reportSource())
        val run = launcherRun(".\\selected launcher", root)

        assertTrue("A relative launcher must resolve inside the selected WSL directory", run is BiomeTargetRun.Node)
        assertReport(run, linuxRoot, emptyList(), node = wslNode)
    }

    fun testWslNodeLauncherMapsFileArguments() {
        val script = Files.writeString(root.resolve("selected launcher"), "#!/missing/node\n" + reportSource())
        val file = Files.writeString(root.resolve("selected file.js"), "let value = 1;\n")
        val run = launcherRun(script.toString(), root, listOf(ProcessCommandParameter.FilePath(file)))

        assertTrue(run is BiomeTargetRun.Node)
        assertReport(run, linuxRoot, listOf("$linuxRoot/selected file.js"), node = wslNode)
    }

    fun testWslShellWrapperRetainsItsEnvironmentSetup() {
        val script = Files.writeString(root.resolve("report.js"), reportSource())
        val wrapper = Files.writeString(root.resolve("shell wrapper"),
            "#!/bin/sh\nexport BIOME_WRAPPER_MARKER='shell setup'\nexec ${shellQuote(wslNode)} ${shellQuote("$linuxRoot/${script.fileName}")} \"\$@\"\n")
        makeExecutable(wrapper)
        val arguments = listOf("argument with spaces")
        val run = launcherRun(wrapper.toString(), root, arguments.map { ProcessCommandParameter.Value(it) })

        assertTrue("Shell wrappers must run their own setup", run is BiomeTargetRun.General)
        assertReport(run, linuxRoot, arguments, "shell setup", node = wslNode)
    }

    fun testWslNativeExecutableUsesDirectDispatch() {
        val nativeNode = "\\\\wsl.localhost\\$distribution" + wslNode.replace('/', '\\')
        val script = Files.writeString(root.resolve("report.js"), reportSource())
        val run = launcherRun(nativeNode, root, listOf(ProcessCommandParameter.FilePath(script)))

        assertTrue("A native WSL executable must run directly", run is BiomeTargetRun.General)
        assertReport(run, linuxRoot, emptyList(), node = wslNode)
    }

    fun testForeignWslInterpreterDoesNotTakeOverSelectedDistribution() {
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(NodeJsInterpreterRef.create(
            WslNodeInterpreter("BiomeUnavailableForeignDistribution", "/missing/node")))
        val script = Files.writeString(root.resolve("selected launcher"), "#!/usr/bin/env node\n" + reportSource())
        makeExecutable(script)
        val run = launcherRun(script.toString(), root)

        assertTrue("A foreign interpreter must retain the launcher's direct WSL route", run is BiomeTargetRun.General)
        assertReport(run, linuxRoot, emptyList())
    }

    fun testLocalInterpreterDoesNotTakeOverWslLauncher() {
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(NodeJsInterpreterRef.create(
            NodeJsLocalInterpreter(windowsNode.toString())))
        val script = Files.writeString(root.resolve("selected launcher"), "#!/usr/bin/env node\n" + reportSource())
        makeExecutable(script)
        val run = launcherRun(script.toString(), root)

        assertTrue("A local interpreter must not execute a WSL launcher", run is BiomeTargetRun.General)
        assertReport(run, linuxRoot, emptyList())
    }

    fun testWslInterpreterDoesNotTakeOverLocalExecutable() {
        val directory = Path.of(myFixture.tempDirPath)
        val script = Files.writeString(directory.resolve("local report.js"), reportSource())
        val run = launcherRun(windowsNode.toString(), directory, listOf(ProcessCommandParameter.FilePath(script)))

        assertTrue("A local native executable must remain local", run is BiomeTargetRun.General)
        assertReport(run, directory.toRealPath().toString(), emptyList(), node = windowsNode.toRealPath().toString())
    }

    private fun makeExecutable(path: Path) {
        val process = ProcessBuilder("wsl.exe", "--distribution", distribution, "--exec", "chmod", "+x",
            "$linuxRoot/${path.fileName}").redirectErrorStream(true).start()
        try {
            assertTrue("WSL fixture chmod timed out", process.waitFor(20, TimeUnit.SECONDS))
            assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                check(process.waitFor(2, TimeUnit.SECONDS)) { "WSL fixture chmod did not terminate" }
            }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
