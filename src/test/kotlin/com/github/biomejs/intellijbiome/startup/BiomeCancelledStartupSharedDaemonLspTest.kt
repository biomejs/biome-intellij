package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.NodeProcessCommandBuilder
import com.github.biomejs.intellijbiome.ProcessCommandParameter
import com.github.biomejs.intellijbiome.launcher.findNativeBiomeExecutable
import com.github.biomejs.intellijbiome.launcher.createBiomeProcessDirectory
import com.github.biomejs.intellijbiome.launcher.findIsolatedBiomeSocket
import com.github.biomejs.intellijbiome.launcher.isolatedBiomeEnvironment
import com.github.biomejs.intellijbiome.launcher.isProcessExecuting
import com.github.biomejs.intellijbiome.launcher.setTestEnvironment
import com.github.biomejs.intellijbiome.lsp.BiomeLspFixtureTestCase
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.KillableProcess
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.target.TargetEnvironment
import com.intellij.execution.target.TargetedCommandLine
import com.intellij.execution.target.TargetedCommandLineBuilder
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.NodeTargetRunOptions
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.NodeTargetRunSetup
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.EnvironmentUtil
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeCancelledStartupSharedDaemonLspTest : BiomeLspFixtureTestCase() {
    fun testCancellingFinalNativeProxyPreservesAlreadySharedDaemon() =
        assertCancelledStartupPreservesSharedDaemon(useNodeLauncher = false)

    fun testCancellingFinalNodeTargetLauncherPreservesAlreadySharedDaemon() =
        assertCancelledStartupPreservesSharedDaemon(useNodeLauncher = true)

    fun testCancellingManualShellLauncherStopsSuspendedImmediateProxy() =
        assertCancelledStartupPreservesSharedDaemon(useNodeLauncher = false, useShellLauncher = true)

    fun testFailedNodeTargetShutdownPreservesAlreadySharedDaemon() =
        assertCancelledStartupPreservesSharedDaemon(useNodeLauncher = true, failShutdownAfterHandoff = true)

    fun testFailedNativeProxyShutdownPreservesAlreadySharedDaemon() =
        assertCancelledStartupPreservesSharedDaemon(useNodeLauncher = false, failShutdownAfterHandoff = true)

    fun testFailedManualShellNpmShutdownPreservesAlreadySharedDaemon() =
        assertCancelledStartupPreservesSharedDaemon(useNodeLauncher = false,
            failShutdownAfterHandoff = true, useNpmShellLauncher = true)

    fun testHardKillingNodeTargetPreservesAlreadySharedDaemon() =
        assertCancelledStartupPreservesSharedDaemon(useNodeLauncher = true,
            failShutdownAfterHandoff = true, hardKillAfterHandoff = true)

    fun testHardKillingNativeProxyPreservesAlreadySharedDaemon() =
        assertCancelledStartupPreservesSharedDaemon(useNodeLauncher = false,
            failShutdownAfterHandoff = true, hardKillAfterHandoff = true)

    private fun assertCancelledStartupPreservesSharedDaemon(useNodeLauncher: Boolean,
        failShutdownAfterHandoff: Boolean = false, hardKillAfterHandoff: Boolean = false,
        useShellLauncher: Boolean = false, useNpmShellLauncher: Boolean = false) {
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        val root = myFixture.tempDirFixture.findOrCreateDir(".")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root)
        myFixture.tempDirFixture.createFile("biome.json", "{}")
        val source = myFixture.tempDirFixture.createFile("index.js", "const value=1;\n")
        val native = findNativeBiomeExecutable(root.toNioPath())
        val node = if (useNodeLauncher) configureNodeInterpreter() else null
        val shellNode = if (useNpmShellLauncher) configureNodeInterpreter() else null
        val npmLauncher = root.toNioPath().resolve("node_modules/@biomejs/biome/bin/biome")
        val executable = when {
            useNodeLauncher -> npmLauncher
            shellNode != null -> createNpmShellLauncher(root.toNioPath(), shellNode, npmLauncher)
            useShellLauncher -> createShellLauncher(root.toNioPath(), native)
            else -> native
        }
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = executable.toString()
            configPath = ""
        }
        val originalEnvironment = EnvironmentUtil.getEnvironmentMap().toMap()
        val cache = createBiomeProcessDirectory("bh-")
        val environment = isolatedBiomeEnvironment(originalEnvironment, cache)
        val created = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstProcess = AtomicReference<Process>()
        val handlerRef = AtomicReference<OSProcessHandler>()
        val directNativeLaunch = hardKillAfterHandoff && !useNodeLauncher
        var directHandler: CompletableFuture<OSProcessHandler>? = null
        var daemon: ProcessHandle? = null
        var nativeProxy: ProcessHandle? = null
        var npmLauncherProcess: ProcessHandle? = null
        var suspendedProxy: ProcessHandle? = null
        var peerProcess: Process? = null
        val executor = Executors.newCachedThreadPool()
        val manager = LspServerManager.getInstance(project)
        try {
            setTestEnvironment(environment)
            val descriptors = mutableListOf<LspServerDescriptor>()
            BiomeLspServerSupportProvider().fileOpened(project, source,
                object : LspServerSupportProvider.LspServerStarter {
                    override fun ensureServerStarted(descriptor: LspServerDescriptor) { descriptors += descriptor }
                })
            val descriptor = descriptors.single()
            fun awaitCancellation(process: Process) {
                firstProcess.set(process)
                created.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "Final proxy creation was not released" }
            }
            val targetField = descriptor.javaClass.getDeclaredField("targetRun").apply { isAccessible = true }
            val run = if (node != null) {
                assertTrue("The configured npm launcher must select the production Node target",
                    targetField.get(descriptor) is BiomeTargetRun.Node)
                val interpreter = object : NodeJsLocalInterpreter(node.toString()) {
                    override fun createTargetRunSetup(project: Project, options: NodeTargetRunOptions): NodeTargetRunSetup {
                        val delegate = super.createTargetRunSetup(project, options)
                        return object : NodeTargetRunSetup(delegate.request, delegate.pathMapper) {
                            override fun configureCommandLine(targetRun: NodeTargetRun) = delegate.configureCommandLine(targetRun)

                            override fun configureEnvironment(builder: TargetedCommandLineBuilder, data: EnvironmentVariablesData) =
                                delegate.configureEnvironment(builder, data)

                            override fun prependNodeDirToPATH(builder: TargetedCommandLineBuilder, data: EnvironmentVariablesData) =
                                delegate.prependNodeDirToPATH(builder, data)

                            override fun createProcessHandler(run: NodeTargetRun, process: Process,
                                commandLine: TargetedCommandLine, environment: TargetEnvironment): KillableProcessHandler {
                                val handler = delegate.createProcessHandler(run, process, commandLine, environment)
                                // Keep the actual Node handler, its readers, and its production kill defaults.
                                handlerRef.set(handler)
                                awaitCancellation(process)
                                return handler
                            }
                        }
                    }
                }
                NodeProcessCommandBuilder(project, interpreter)
                    .setExecutable(executable.toString()).setWorkingDirectory(root.path).setCharset(Charsets.UTF_8)
                    .addParameters(listOf(ProcessCommandParameter.Value("lsp-proxy"))).build()
            } else {
                assertTrue("The manual native or shell executable must select the production General target",
                    targetField.get(descriptor) is BiomeTargetRun.General)
                val command = object : GeneralCommandLine(executable.toString(), "lsp-proxy") {
                    override fun createProcess(): Process = super.createProcess().also(::awaitCancellation)
                }.withWorkDirectory(root.path).withEnvironment(environment)
                BiomeTargetRun.General(command)
            }
            targetField.set(descriptor, run)
            if (directNativeLaunch) {
                // The separate native failure case covers SDK handoff. This control
                // directly exercises the production General handler's hard-kill API.
                directHandler = CompletableFuture.supplyAsync({
                    run.startProcess().also { handler -> handlerRef.set(handler); handler.startNotify() }
                }, executor)
            } else {
                manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
            }
            PlatformTestUtil.waitWithEventsDispatching("SDK did not enter final proxy creation", { created.count == 0L }, 10)
            PlatformTestUtil.waitWithEventsDispatching("First proxy did not spawn its own daemon", {
                daemon = firstProcess.get().descendants().use { processes ->
                    processes.filter { it.info().commandLine().orElse("").contains("__run_server") }
                        .findFirst().orElse(null)
                }
                daemon != null
            }, 10)
            nativeProxy = if (shellNode != null) {
                val shell = firstProcess.get().toHandle()
                npmLauncherProcess = shell.children().use { children ->
                    children.filter {
                        it.info().arguments().orElse(emptyArray()).toList() ==
                            listOf(npmLauncher.toString(), "lsp-proxy")
                    }.findFirst().orElse(null)
                }
                val npmProcess = requireNotNull(npmLauncherProcess) { "The shell must own the official npm launcher directly" }
                val proxy = npmProcess.children().use { children ->
                    children.filter { it.info().arguments().orElse(emptyArray()).toList() == listOf("lsp-proxy") }
                        .findFirst().orElse(null)
                }
                // Retain the owned handle before assertions so cleanup also covers metadata failures.
                nativeProxy = proxy
                requireNotNull(proxy) { "The npm launcher must own an immediate native lsp-proxy" }
                assertEquals("The npm launcher must run in the configured real Node executable",
                    shellNode.toRealPath(), Path.of(npmProcess.info().command().orElseThrow()).toRealPath())
                assertEquals("The grandchild must be the installed native Biome executable",
                    native.toRealPath(), Path.of(proxy.info().command().orElseThrow()).toRealPath())
                assertEquals("The shell, Node launcher and native proxy must be distinct processes",
                    3, setOf(shell.pid(), npmProcess.pid(), proxy.pid()).size)
                proxy
            } else if (useNodeLauncher || useShellLauncher) {
                firstProcess.get().children().use { processes ->
                    processes.filter { it.info().arguments().orElse(emptyArray()).firstOrNull() == "lsp-proxy" }
                        .findFirst().orElse(null)
                }
            } else firstProcess.get().toHandle()
            assertNotNull("The final launcher must own a native lsp-proxy process", nativeProxy)
            if (useNodeLauncher || useShellLauncher) assertTrue("The launcher must spawn a separate immediate native child",
                nativeProxy!!.pid() != firstProcess.get().pid())
            val daemonPid = daemon!!.pid()
            PlatformTestUtil.waitWithEventsDispatching("The owned daemon did not bind its isolated socket", {
                findIsolatedBiomeSocket(environment, "2.5.15") != null
            }, 10)
            assertNotNull("The owned daemon must create its socket in this test's isolated cache",
                findIsolatedBiomeSocket(environment, "2.5.15"))

            peerProcess = ProcessBuilder(native.toString(), "lsp-proxy").directory(root.toNioPath().toFile())
                .apply { environment().putAll(environment) }.start()
            val ready = CompletableFuture<Long>()
            val diagnosed = CompletableFuture<Void>()
            val uri = source.toNioPath().toUri().toString()
            val client = object : LanguageClient {
                override fun telemetryEvent(event: Any?) = Unit
                override fun showMessage(params: MessageParams) = Unit
                override fun showMessageRequest(params: ShowMessageRequestParams) =
                    CompletableFuture.completedFuture<MessageActionItem>(null)
                override fun logMessage(params: MessageParams) {
                    if (params.message.startsWith("Server initialized with PID: ")) {
                        ready.complete(params.message.substringAfterLast(' ').toLong())
                    }
                }
                override fun publishDiagnostics(params: PublishDiagnosticsParams) {
                    if (params.uri == uri) diagnosed.complete(null)
                }
                override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any>> =
                    CompletableFuture.completedFuture(params.items.map { emptyMap<String, Any>() })
                override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> =
                    CompletableFuture.completedFuture(null)
                override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void> =
                    CompletableFuture.completedFuture(null)
            }
            val launcher = LSPLauncher.createClientLauncher(client, peerProcess.inputStream,
                peerProcess.outputStream, executor) { it }
            val listening = launcher.startListening()
            val peer = launcher.remoteProxy
            try {
                val initialization = peer.initialize(InitializeParams().apply {
                    rootUri = root.toNioPath().toUri().toString()
                    workspaceFolders = listOf(WorkspaceFolder(rootUri, "shared-root"))
                    capabilities = ClientCapabilities().apply {
                        workspace = WorkspaceClientCapabilities().apply { configuration = true }
                    }
                }).get(10, TimeUnit.SECONDS)
                assertEquals("2.5.15", initialization.serverInfo.version)
                peer.initialized(InitializedParams())
                assertEquals("Peer must attach to the first proxy's daemon", daemonPid, ready.get(10, TimeUnit.SECONDS))
                peer.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "javascript", 1, "const value=1;\n")))
                diagnosed.get(10, TimeUnit.SECONDS)
                val formatting = DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(2, true))
                assertPeerFormats(peer, formatting)
                val initializing = if (directNativeLaunch) null else
                    manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
                if (initializing != null) assertEquals(LspServerState.Initializing, initializing.state)
                if (failShutdownAfterHandoff) {
                    release.countDown()
                    if (directNativeLaunch) {
                        requireNotNull(directHandler).get(10, TimeUnit.SECONDS)
                    } else {
                        PlatformTestUtil.waitWithEventsDispatching("The actual target was not handed to the SDK", {
                            requireNotNull(initializing).state == LspServerState.Running
                        }, 10)
                    }
                    if (node != null) {
                        assertTrue("The SDK must start the actual Node handler before the failure",
                            requireNotNull(handlerRef.get()).isStartNotified)
                    }
                }
                if (failShutdownAfterHandoff || useShellLauncher) {
                    // Keep the shell case before handoff: its suspended immediate child
                    // cannot exit on stdin EOF, so canceled startup must force owned cleanup.
                    // The shared daemon and the second client remain responsive.
                    suspendedProxy = nativeProxy
                    val suspendProxy = ProcessBuilder("/bin/kill", "-STOP", nativeProxy!!.pid().toString()).start()
                    assertTrue("The owned proxy must be suspended", suspendProxy.waitFor(5, TimeUnit.SECONDS))
                    assertEquals(0, suspendProxy.exitValue())
                    if (useShellLauncher) {
                        assertTrue("The shell must remain alive before canceled startup cleanup", firstProcess.get().isAlive)
                        assertEquals("The suspended proxy must remain the shell's immediate child",
                            firstProcess.get().pid(), nativeProxy!!.parent().orElse(null)?.pid())
                    }
                    if (useNpmShellLauncher) {
                        val npmProcess = requireNotNull(npmLauncherProcess)
                        assertEquals("The suspended proxy's Node launcher must still be the shell's immediate child",
                            firstProcess.get().pid(), npmProcess.parent().orElse(null)?.pid())
                        assertEquals("The suspended proxy must still be the Node launcher's immediate child",
                            npmProcess.pid(), nativeProxy!!.parent().orElse(null)?.pid())
                    }
                    assertPeerFormats(peer, formatting)
                }
                if (hardKillAfterHandoff) {
                    // Exercise the SDK handler's direct hard-kill entrypoint before any
                    // manager stop can run the normal processWillTerminate/destroy path.
                    (requireNotNull(handlerRef.get()) as KillableProcess).killProcess()
                } else {
                    manager.stopServers(BiomeLspServerSupportProvider::class.java)
                }
                release.countDown()
                PlatformTestUtil.waitWithEventsDispatching("Stopped proxy remained alive", {
                    !firstProcess.get().isAlive
                }, if (failShutdownAfterHandoff) 15 else 5)
                assertTrue("Stopping this client killed the daemon already serving the second client", isProcessExecuting(daemonPid))
                PlatformTestUtil.waitWithEventsDispatching("Canceled launcher's native proxy child remained alive", {
                    !isProcessExecuting(nativeProxy!!.pid())
                }, 5)
                npmLauncherProcess?.let { npmProcess ->
                    PlatformTestUtil.waitWithEventsDispatching("The owned npm launcher remained alive", {
                        !isProcessExecuting(npmProcess.pid())
                    }, 5)
                }
                if (node != null || directNativeLaunch) {
                    val handler = requireNotNull(handlerRef.get()) { "The production handler was not captured" }
                    assertTrue("The production handler readers must be started", handler.isStartNotified)
                    assertTrue("The production handler readers must finish while the shared daemon remains alive",
                        handler.waitFor(2_000))
                    if (node != null) {
                        assertEquals(node.toString(), (NodeTargetRun.getTargetRun(handler).interpreter as NodeJsLocalInterpreter).interpreterSystemDependentPath)
                    }
                }
                assertTrue("Canceling the first startup killed a daemon already serving the second client", isProcessExecuting(daemonPid))
                assertPeerFormats(peer, formatting)
                if (!hardKillAfterHandoff) assertEquals(LspServerState.ShutdownNormally, requireNotNull(initializing).state)
                peer.shutdown().get(5, TimeUnit.SECONDS)
                peer.exit()
            } finally {
                peerProcess.outputStream.close()
                peerProcess.destroy()
                listening.cancel(true)
            }
        } finally {
            suspendedProxy?.let { process ->
                if (isProcessExecuting(process.pid())) {
                    ProcessBuilder("/bin/kill", "-CONT", process.pid().toString())
                        .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
                }
            }
            release.countDown()
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
            firstProcess.get()?.let { if (it.isAlive) it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) }
            peerProcess?.let { if (it.isAlive) it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) }
            nativeProxy?.let { if (isProcessExecuting(it.pid())) it.destroyForcibly() }
            npmLauncherProcess?.let { if (isProcessExecuting(it.pid())) it.destroyForcibly() }
            daemon?.let { if (isProcessExecuting(it.pid())) it.destroyForcibly() }
            handlerRef.get()?.let { handler ->
                if (!handler.isStartNotified) handler.startNotify()
                assertTrue("The owned handler must finish during test cleanup", handler.waitFor(5_000))
            }
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            setTestEnvironment(originalEnvironment)
            FileUtil.delete(cache.toFile())
        }
    }

    private fun createShellLauncher(root: Path, native: Path): Path {
        val wrapper = root.resolve("biome-wrapper.sh")
        val quotedNative = "'${native.toString().replace("'", "'\\''")}'"
        // Keep the native proxy in the foreground and prevent shell tail-exec optimization.
        Files.writeString(wrapper, "#!/bin/sh\n$quotedNative \"\$@\"\nstatus=\$?\nexit \"\$status\"\n")
        check(wrapper.toFile().setExecutable(true)) { "The manual shell launcher must be executable" }
        return wrapper
    }

    private fun createNpmShellLauncher(root: Path, node: Path, npmLauncher: Path): Path {
        val wrapper = root.resolve("biome-npm-wrapper.sh")
        val quotedNode = "'${node.toString().replace("'", "'\\''")}'"
        val quotedLauncher = "'${npmLauncher.toString().replace("'", "'\\''")}'"
        // Preserve shell -> Node -> native proxy: no exec and no background command.
        Files.writeString(wrapper, "#!/bin/sh\n$quotedNode $quotedLauncher \"\$@\"\nstatus=\$?\nexit \"\$status\"\n")
        check(wrapper.toFile().setExecutable(true)) { "The manual npm shell launcher must be executable" }
        return wrapper
    }

    private fun configureNodeInterpreter(): Path {
        val node = System.getenv("PATH").split(File.pathSeparator).asSequence()
            .map { Path.of(it, "node") }.firstOrNull { Files.isExecutable(it) }
            ?: error("Node is required for the npm launcher cancellation test")
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(
            NodeJsInterpreterRef.create(NodeJsLocalInterpreter(node.toString())), testRootDisposable)
        return node
    }

    private fun assertPeerFormats(peer: LanguageServer, params: DocumentFormattingParams) {
        val input = "const value=1;\n"
        val document = com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(input)
        val edits = peer.textDocumentService.formatting(params).get(10, TimeUnit.SECONDS)
            ?: error("Shared client returned no formatting edits")
        val output = StringBuilder(input)
        edits.map { edit ->
            Triple(document.getLineStartOffset(edit.range.start.line) + edit.range.start.character,
                document.getLineStartOffset(edit.range.end.line) + edit.range.end.character, edit.newText)
        }.sortedByDescending { it.first }.forEach { (start, end, text) -> output.replace(start, end, text) }
        assertEquals("const value = 1;\n", output.toString())
    }
}
