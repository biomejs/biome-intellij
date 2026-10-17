package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.BiomeTargetRun
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
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
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

    fun testCancellingFinalNpmLauncherPreservesAlreadySharedDaemon() =
        assertCancelledStartupPreservesSharedDaemon(useNodeLauncher = true)

    private fun assertCancelledStartupPreservesSharedDaemon(useNodeLauncher: Boolean) {
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        val root = myFixture.tempDirFixture.findOrCreateDir(".")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root)
        myFixture.tempDirFixture.createFile("biome.json", "{}")
        val source = myFixture.tempDirFixture.createFile("index.js", "const value=1;\n")
        val native = findNativeBiomeExecutable(root.toNioPath())
        val node = if (useNodeLauncher) configureNodeInterpreter() else null
        val executable = if (useNodeLauncher) root.toNioPath().resolve("node_modules/@biomejs/biome/bin/biome") else native
        val commandArguments = listOfNotNull(node?.toString(), executable.toString(), "lsp-proxy")
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
        var daemon: ProcessHandle? = null
        var nativeProxy: ProcessHandle? = null
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
            // The barrier uses a General handler for both routes. The npm route still runs
            // the configured Node executable, unchanged npm launcher, and real native child.
            val command = object : GeneralCommandLine(commandArguments) {
                override fun createProcess(): Process {
                    val process = super.createProcess()
                    firstProcess.set(process)
                    created.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "Final proxy creation was not released" }
                    return process
                }
            }.withWorkDirectory(root.path).withEnvironment(environment)
            descriptor.javaClass.getDeclaredField("targetRun").apply { isAccessible = true }
                .set(descriptor, BiomeTargetRun.General(command))
            manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
            PlatformTestUtil.waitWithEventsDispatching("SDK did not enter final proxy creation", { created.count == 0L }, 10)
            PlatformTestUtil.waitWithEventsDispatching("First proxy did not spawn its own daemon", {
                daemon = firstProcess.get().descendants().use { processes ->
                    processes.filter { it.info().commandLine().orElse("").contains("__run_server") }
                        .findFirst().orElse(null)
                }
                daemon != null
            }, 10)
            nativeProxy = if (useNodeLauncher) {
                firstProcess.get().descendants().use { processes ->
                    processes.filter { it.info().arguments().orElse(emptyArray()).contains("lsp-proxy") }
                        .findFirst().orElse(null)
                }
            } else firstProcess.get().toHandle()
            assertNotNull("The final launcher must own a native lsp-proxy process", nativeProxy)
            if (useNodeLauncher) assertTrue("The npm launcher must spawn a separate native child",
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
                val initializing = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
                assertEquals(LspServerState.Initializing, initializing.state)
                manager.stopServers(BiomeLspServerSupportProvider::class.java)
                release.countDown()
                PlatformTestUtil.waitWithEventsDispatching("Canceled proxy remained alive", {
                    !firstProcess.get().isAlive
                }, 5)
                PlatformTestUtil.waitWithEventsDispatching("Canceled launcher's native proxy child remained alive", {
                    !isProcessExecuting(nativeProxy!!.pid())
                }, 5)
                assertTrue("Canceling the first startup killed a daemon already serving the second client", isProcessExecuting(daemonPid))
                assertPeerFormats(peer, formatting)
                assertEquals(LspServerState.ShutdownNormally, initializing.state)
                peer.shutdown().get(5, TimeUnit.SECONDS)
                peer.exit()
            } finally {
                peerProcess.outputStream.close()
                peerProcess.destroy()
                listening.cancel(true)
            }
        } finally {
            release.countDown()
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
            firstProcess.get()?.let { if (it.isAlive) it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) }
            peerProcess?.let { if (it.isAlive) it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) }
            nativeProxy?.let { if (isProcessExecuting(it.pid())) it.destroyForcibly() }
            daemon?.let { if (isProcessExecuting(it.pid())) it.destroyForcibly() }
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            setTestEnvironment(originalEnvironment)
            FileUtil.delete(cache.toFile())
        }
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
