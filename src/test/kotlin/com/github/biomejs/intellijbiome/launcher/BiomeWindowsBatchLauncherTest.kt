package com.github.biomejs.intellijbiome.launcher

import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.ProcessCommandParameter
import java.nio.file.Files
import java.nio.file.Path

class BiomeWindowsBatchLauncherTest : BiomeWindowsLauncherTestCase() {
    fun testCmdLauncherPreservesArgumentsWorkingDirectoryEnvironmentAndInput() = checkBatchLauncher("cmd")

    fun testBatLauncherPreservesArgumentsWorkingDirectoryEnvironmentAndInput() = checkBatchLauncher("bat")

    private fun checkBatchLauncher(extension: String) {
        val directory = Files.createDirectories(Path.of(myFixture.tempDirPath, "selected working directory"))
        val script = Files.writeString(directory.resolve("report.js"), reportSource(readInput = true))
        val executable = batchLauncher(directory.resolve("selected launcher.$extension"), script)
        val arguments = listOf("argument with spaces", "file&name.js", "(parentheses).js", "caret^name.js", "")
        val input = myFixture.tempDirFixture.createFile("input.js")
        Files.writeString(input.toNioPath(), "first\r\nsecond\r\n")
        val run = launcherRun(executable.toString(), directory,
            arguments.map { ProcessCommandParameter.Value(it) }, input)

        assertTrue("Batch wrappers must keep their environment setup", run is BiomeTargetRun.General)
        assertReport(run, directory.toRealPath().toString(), arguments, "batch setup", "first\r\nsecond\r\n")
    }

    fun testRelativeBatchLauncherUsesSelectedWorkingDirectory() {
        val directory = Files.createDirectories(Path.of(myFixture.tempDirPath, "nested working directory"))
        val script = Files.writeString(directory.resolve("report.js"), reportSource())
        batchLauncher(directory.resolve("selected launcher.cmd"), script)
        val arguments = listOf("selected root")
        val run = launcherRun(".\\selected launcher.cmd", directory, arguments.map { ProcessCommandParameter.Value(it) })

        assertTrue(run is BiomeTargetRun.General)
        assertReport(run, directory.toRealPath().toString(), arguments, "batch setup")
    }

    fun testBatchLauncherPreservesCrLfOutput() {
        val directory = Path.of(myFixture.tempDirPath)
        val script = Files.writeString(directory.resolve("report.js"), "process.stdout.write('first\\r\\nsecond\\r\\n');")
        val executable = batchLauncher(directory.resolve("selected launcher.cmd"), script)

        assertEquals("first\r\nsecond\r\n", output(launcherRun(executable.toString(), directory)).stdout)
    }

    fun testNativeExecutableUsesDirectDispatch() {
        val directory = Files.createDirectories(Path.of(myFixture.tempDirPath, "native working directory"))
        val script = Files.writeString(directory.resolve("report.js"), reportSource())
        val arguments = listOf("argument with spaces", "file&name.js")
        val run = launcherRun(windowsNode.toString(), directory,
            listOf(ProcessCommandParameter.FilePath(script)) + arguments.map { ProcessCommandParameter.Value(it) })

        assertTrue("A native .exe must run directly", run is BiomeTargetRun.General)
        assertReport(run, directory.toRealPath().toString(), arguments, node = windowsNode.toRealPath().toString())
    }

    private fun batchLauncher(path: Path, script: Path): Path = Files.writeString(path,
        "@echo off\r\nset \"BIOME_WRAPPER_MARKER=batch setup\"\r\n\"$windowsNode\" \"$script\" %*\r\n")
}
