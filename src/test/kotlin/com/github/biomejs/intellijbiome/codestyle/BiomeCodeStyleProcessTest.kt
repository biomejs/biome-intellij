package com.github.biomejs.intellijbiome.codestyle

import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.launcher.createBiomeProcessDirectory
import com.github.biomejs.intellijbiome.launcher.isProcessExecuting
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.EnvironmentUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class BiomeCodeStyleProcessTest : BasePlatformTestCase() {
    fun testTimeoutStopsTheOwnedLauncherAndChild() = withOwnedProcess { run, pids ->
        try {
            runWithModalProgressBlocking(project, "Timeout test import") { runBiomeCodeStyleProbe(run, 2_000) }
            fail("A stalled Biome process must time out")
        } catch (_: ExecutionException) {
            assertStopped(pids)
        }
    }

    fun testCancellationPropagatesAndStopsTheOwnedLauncherAndChild() = withOwnedProcess { run, pids ->
        runWithModalProgressBlocking(project, "Cancel test import") {
            coroutineScope {
                val pending = async { runBiomeCodeStyleProbe(run) }
                withTimeout(5_000) { while (!Files.exists(pids)) delay(10) }
                pending.cancel(CancellationException("Cancel import test"))
                try {
                    pending.await()
                    fail("Cancellation must not become a successful import")
                } catch (_: CancellationException) {
                    pending.join()
                }
            }
        }
        assertStopped(pids)
    }

    fun testEnclosingTimeoutPropagates() = withOwnedProcess { run, pids ->
        runWithModalProgressBlocking(project, "Outer timeout test import") {
            try {
                withTimeout(2_000) { runBiomeCodeStyleProbe(run) }
                fail("An enclosing timeout must remain cancellation")
            } catch (_: TimeoutCancellationException) {
                // Check the coroutine boundary before modal progress translates cancellation
                // to a platform ProcessCanceledException. Ordinary import failures still escape.
            }
        }
        assertStopped(pids)
    }

    fun testExcessOutputIsRejectedAndOwnedProcessStops() = withOwnedProcess(oversized = true) { run, pids ->
        try {
            runWithModalProgressBlocking(project, "Bound output test import") { runBiomeCodeStyleProbe(run) }
            fail("Oversized process output must not be imported")
        } catch (_: ExecutionException) {
            assertStopped(pids)
        }
    }

    private fun withOwnedProcess(oversized: Boolean = false, test: (BiomeTargetRun, Path) -> Unit) {
        val root = createBiomeProcessDirectory("biome-import-")
        val pids = root.resolve("owned import pids")
        val script = root.resolve("import fixture.js")
        Files.writeString(script, """
            const fs = require('fs');
            const child = require('child_process').spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {stdio: 'ignore'});
            fs.writeFileSync(process.argv[2] + '.tmp', process.pid + '\n' + child.pid);
            fs.renameSync(process.argv[2] + '.tmp', process.argv[2]);
            process.stdin.resume();
            if ($oversized) process.stdout.write('x'.repeat(70000));
            setInterval(() => {}, 1000);
        """.trimIndent())
        val node = EnvironmentUtil.getEnvironmentMap().getValue("PATH").split(File.pathSeparator)
            .map { Path.of(it, "node") }.firstOrNull(Files::isExecutable) ?: error("Node is required")
        val run = BiomeTargetRun.General(GeneralCommandLine(node.toString(), script.toString(), pids.toString()))
        try {
            test(run, pids)
        } finally {
            try {
                if (Files.exists(pids)) {
                    Files.readAllLines(pids).map(String::toLong).forEach { pid ->
                        ProcessHandle.of(pid).orElse(null)?.takeIf { it.isAlive }?.destroyForcibly()
                    }
                    assertStopped(pids)
                }
            } finally {
                FileUtil.delete(root.toFile())
            }
        }
    }

    private fun assertStopped(pids: Path) {
        assertTrue("Owned process fixture must have started", Files.exists(pids))
        val owned = Files.readAllLines(pids).map(String::toLong)
        PlatformTestUtil.waitWithEventsDispatching("Owned importer processes did not stop", {
            owned.none(::isProcessExecuting)
        }, 5)
        assertTrue(owned.none(::isProcessExecuting))
    }
}
