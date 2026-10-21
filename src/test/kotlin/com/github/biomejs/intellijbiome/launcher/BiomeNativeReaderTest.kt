package com.github.biomejs.intellijbiome.launcher

import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.NodeProcessCommandBuilder
import com.github.biomejs.intellijbiome.ProcessCommandParameter
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.util.EnvironmentUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BiomeNativeReaderTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private lateinit var interpreter: NodeJsLocalInterpreter

    override fun setUp() {
        super.setUp()
        assertTrue("Inherited-pipe fixtures require macOS or Linux", SystemInfoRt.isMac || SystemInfoRt.isLinux)
        val node = EnvironmentUtil.getEnvironmentMap().getValue("PATH").split(java.io.File.pathSeparator)
            .map { Path.of(it, "node") }.firstOrNull(Files::isExecutable) ?: error("Node is required")
        interpreter = NodeJsLocalInterpreter(node.toString())
    }

    fun testNodeReaderFinishesAfterProxyExitWithInheritedPipes() = checkInheritedPipes(terminateExternally = false)

    fun testNodeReaderFinishesAfterExternalProxyTerminationWithInheritedPipes() = checkInheritedPipes(terminateExternally = true)

    private fun checkInheritedPipes(terminateExternally: Boolean) {
        val root = Path.of(myFixture.tempDirPath)
        val release = root.resolve("release owned child")
        val childPid = root.resolve("owned child pid")
        val script = root.resolve("proxy with inherited pipes.js")
        val finalOutputWritten = root.resolve("final output written")
        val stdout = "stdout café\r\nlast stdout"
        val stderr = "stderr café\r\nlast stderr"
        val stdoutTail = "\r\nfinal stdout déjà vu\r\n"
        val stderrTail = "\r\nfinal stderr déjà vu\r\n"
        Files.writeString(script, """
            #!/usr/bin/env node
            const fs = require('fs');
            const child = require('child_process').spawn(process.execPath, ['-e', `
                const fs = require('fs');
                setInterval(() => {
                    if (fs.existsSync(process.argv[1])) process.exit(0);
                }, 10);
            `, process.argv[2]], {stdio: ['ignore', 'inherit', 'inherit']});
            fs.writeFileSync(process.argv[3], String(child.pid));
            child.unref();
            process.stdout.write('stdout café\r\nlast stdout');
            process.stderr.write('stderr café\r\nlast stderr');
            process.stdin.once('data', () => {
                fs.writeSync(1, '\r\nfinal stdout déjà vu\r\n');
                fs.writeSync(2, '\r\nfinal stderr déjà vu\r\n');
                fs.writeFileSync(process.argv[4], 'written');
                if (${!terminateExternally}) process.exit(0);
            });
        """.trimIndent())
        val run = NodeProcessCommandBuilder(project, interpreter)
            .setExecutable(script.toString()).setWorkingDirectory(root.toString()).setCharset(Charsets.UTF_8)
            .addParameters(listOf(release, childPid, finalOutputWritten).map { ProcessCommandParameter.Value(it.toString()) }).build()
        assertTrue("The regression must exercise the native Node target handler", run is BiomeTargetRun.Node)
        val handler = run.startProcess()
        handler.setShouldDestroyProcessRecursively(false)
        (handler as KillableProcessHandler).setShouldKillProcessSoftly(false)
        val streamsReady = CountDownLatch(2)
        val terminated = CompletableFuture<Int>()
        val stdoutSeen = StringBuffer()
        val stderrSeen = StringBuffer()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val seen = when (outputType) {
                    ProcessOutputTypes.STDOUT -> stdoutSeen
                    ProcessOutputTypes.STDERR -> stderrSeen
                    else -> return
                }
                val expected = if (outputType == ProcessOutputTypes.STDOUT) stdout else stderr
                seen.append(event.text)
                if (seen.toString() == expected) streamsReady.countDown()
            }

            override fun processTerminated(event: ProcessEvent) {
                terminated.complete(event.exitCode)
            }
        })
        if (!handler.isStartNotified) handler.startNotify()
        var child: ProcessHandle? = null
        try {
            assertTrue("Both native readers must receive exact CRLF output before stopping", streamsReady.await(5, TimeUnit.SECONDS))
            child = ProcessHandle.of(Files.readString(childPid).toLong()).orElseThrow()
            assertTrue("Owned child must remain alive holding inherited pipes", isProcessExecuting(child.pid()))
            handler.processInput!!.apply { write("stop\n".toByteArray()); flush() }
            if (terminateExternally) {
                // Signal only after the fixture has written its final chunks, without waiting
                // for the readers to deliver them. Completion must drain those chunks.
                PlatformTestUtil.waitWithEventsDispatching("Proxy did not write its final output", {
                    Files.exists(finalOutputWritten)
                }, 5)
                // Process.destroy() closes the Java streams immediately; an external signal preserves their drain path.
                assertTrue("The owned proxy must receive external termination", handler.process.toHandle().destroy())
            }
            assertTrue("Proxy itself must exit", handler.process.waitFor(5, TimeUnit.SECONDS))
            assertTrue("Native readers must finish while the inherited-pipe child stays alive", handler.waitFor(2_000))
            assertTrue("Stopping this client must not kill the inherited-pipe child", isProcessExecuting(child.pid()))
            val exitCode = terminated.get(1, TimeUnit.SECONDS)
            if (!terminateExternally) assertEquals(0, exitCode)
            assertEquals("stdout must retain exact UTF-8 and CRLF output", stdout + stdoutTail, stdoutSeen.toString())
            assertEquals("stderr must retain exact UTF-8 and CRLF output", stderr + stderrTail, stderrSeen.toString())
        } finally {
            // Release only this fixture's child, including after the expected failing control.
            Files.writeString(release, "release")
            // The fixture directory must outlive the child observing its release marker.
            val ownedChild = child ?: if (Files.exists(childPid))
                ProcessHandle.of(Files.readString(childPid).toLong()).orElse(null) else null
            try {
                if (ownedChild != null && !awaitChildExit(ownedChild)) {
                    ownedChild.destroyForcibly()
                    assertTrue("Owned child must stop before removing its release marker", awaitChildExit(ownedChild))
                }
            } finally {
                if (handler.process.isAlive) handler.destroyProcess()
                assertTrue("Owned proxy and reader cleanup must finish", handler.waitFor(5_000))
            }
        }
    }

    private fun awaitChildExit(child: ProcessHandle): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (isProcessExecuting(child.pid()) && System.nanoTime() < deadline) Thread.sleep(10)
        return !isProcessExecuting(child.pid())
    }
}
