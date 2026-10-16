package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.launcher.findNativeBiomeExecutable
import com.github.biomejs.intellijbiome.launcher.createBiomeProcessDirectory
import com.github.biomejs.intellijbiome.launcher.findIsolatedBiomeSocket
import com.github.biomejs.intellijbiome.launcher.isolatedBiomeEnvironment
import com.github.biomejs.intellijbiome.launcher.isProcessExecuting
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.EnvironmentUtil
import kotlinx.coroutines.*

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeSharedDaemonLspTest : BiomeSharedDaemonFixtureTestCase("2.5.15") {
    fun testNodeRestartPreservesSharedDaemonAndCleansUpProxies() = checkLifecycle(nativePrimary = false)

    fun testNativeRestartPreservesSharedDaemonAndCleansUpProxies() = checkLifecycle(nativePrimary = true)

    fun testNodeStopPreservesSharedDaemonAndCleansUpProxies() =
        checkLifecycle(nativePrimary = false, restartPrimary = false)

    fun testNativeStopPreservesSharedDaemonAndCleansUpProxies() =
        checkLifecycle(nativePrimary = true, restartPrimary = false)
}

abstract class BiomeSharedDaemonFixtureTestCase(private val biomeVersion: String) : BiomeLspFixtureTestCase() {
    private lateinit var root: VirtualFile
    private lateinit var source: VirtualFile

    private lateinit var events: RuntimeGateEvents

    override fun setUp() {
        super.setUp()
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
        events = RuntimeGateEvents(project, testRootDisposable)
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        root = myFixture.tempDirFixture.findOrCreateDir(".")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root)
        myFixture.tempDirFixture.createFile("biome.json", """{"javascript":{"formatter":{"quoteStyle":"single"}}}""")
        source = myFixture.tempDirFixture.createFile("index.js", "const message=\"hello\";\n")
    }

    private fun establishPrimaryServer(): LspServer {
        myFixture.configureFromExistingVirtualFile(source)
        waitUntilFileOpenedByLspServer(project, source, timeout = 20)
        // Establish initial document readiness before testing restart; never reopen
        // either file after that action.
        awaitInitialReadiness(source, project, myFixture, events)
        val original = servers().single { it.descriptor.roots.single() == root }
        assertEquals(biomeVersion, original.initializeResult?.serverInfo?.version)
        formatAndAssert()
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("const message=\"hello\";\n") }
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
        return original
    }

    protected fun checkLifecycle(nativePrimary: Boolean, restartPrimary: Boolean = true) {
        val originalEnvironment = EnvironmentUtil.getEnvironmentMap().toMap()
        // Both clients must share a daemon created by this scenario. A checkout-wide
        // cache can legitimately reuse a daemon owned by an earlier fixture or JVM.
        // Keep the temporary path short enough for Biome's Unix-domain socket.
        val cache = createBiomeProcessDirectory("bs-")
        val environment = isolatedBiomeEnvironment(originalEnvironment, cache)
        try {
            com.github.biomejs.intellijbiome.launcher.setTestEnvironment(
                environment
            )
            checkLifecycleInIsolatedCache(nativePrimary, restartPrimary, environment)
        } finally {
            try {
                com.github.biomejs.intellijbiome.launcher.setTestEnvironment(originalEnvironment)
            } finally {
                FileUtil.delete(cache.toFile())
            }
        }
    }

    private fun checkLifecycleInIsolatedCache(nativePrimary: Boolean, restartPrimary: Boolean, environment: Map<String, String>) {
        if (nativePrimary) {
            val native = findNativeBiomeExecutable(root.toNioPath())
            BiomeSettings.getInstance(project).apply {
                configurationMode = ConfigurationMode.MANUAL
                executablePath = native.toString()
            }
        }
        val original = establishPrimaryServer()
        assertNotNull("The primary daemon must create its socket in this test's isolated cache",
            findIsolatedBiomeSocket(environment, biomeVersion))
        com.intellij.ide.bookmarks.BookmarkManager.getInstance(project)
        // Establish still-live primary native state before the secondary fixture
        // snapshots global listener and package.json-pointer leak baselines.
        com.intellij.refactoring.suggested.SuggestedRefactoringProvider.getInstance(project)
        myFixture.doHighlighting()
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val builder = factory.createFixtureBuilder("${name}-other-project")
        val otherFixture = factory.createCodeInsightFixture(builder.fixture)
        builder.addModule(EmptyModuleFixtureBuilder::class.java).addSourceContentRoot(otherFixture.tempDirPath)
        otherFixture.setUp()
        var otherRoot: VirtualFile? = null
        var primaryFailure: Throwable? = null
        try {
            val otherProject = otherFixture.project
            configureLocalNodeForRuntimeTests(otherProject, otherFixture.testRootDisposable)
            otherFixture.testDataPath = myFixture.testDataPath
            otherRoot = otherFixture.tempDirFixture.findOrCreateDir(".")
            TestNpmPackageInstaller(otherFixture).installForTest(javaClass, otherRoot)
            otherFixture.tempDirFixture.createFile("biome.json", "{}")
            val other = otherFixture.tempDirFixture.createFile("other.js", "const other=1;\n")
            val otherEvents = RuntimeGateEvents(otherProject, otherFixture.testRootDisposable)
            otherFixture.configureFromExistingVirtualFile(other)
            waitUntilFileOpenedByLspServer(otherProject, other, timeout = 20)
            awaitInitialReadiness(other, otherProject, otherFixture, otherEvents)
            val otherManager = LspServerManager.getInstance(otherProject)
            val otherServer = otherManager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
            assertEquals(biomeVersion, otherServer.initializeResult?.serverInfo?.version)
            assertFormattingResponse(otherServer, other, "const other = 1;\n")
            val before = processes(root, otherRoot)
            val originalProxies = before.filter { it.command.contains(" lsp-proxy") && it.command.contains(root.path) }
            assertEquals("Expected the original native proxy and optional Node wrapper", if (nativePrimary) 1 else 2, originalProxies.size)
            val daemon = before.single { it.command.contains(" __run_server ") }
            assertTrue("Daemon must start as a descendant of the first project's proxy",
                originalProxies.any { it.pid == daemon.parent })
            logProcesses("Before primary lifecycle change", before)
            if (restartPrimary) {
                project.service<BiomeServerService>().restartBiomeServer()
                PlatformTestUtil.waitWithEventsDispatching("Existing restart did not replace the primary server", {
                    servers().singleOrNull()?.let { it !== original && it.state == LspServerState.Running } == true
                }, 20)
                val replacement = servers().single()
                logProcesses("After primary restart", processes(root, otherRoot))
                assertEquals(listOf(otherServer), otherManager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList())
                assertEquals(LspServerState.Running, otherServer.state)
                assertEquals(biomeVersion, otherServer.initializeResult?.serverInfo?.version)
                // The shared daemon can cancel reads while replacement didOpen writes
                // document state. Establish recovery before querying the other client.
                waitUntilFileOpenedByLspServer(project, source, timeout = 20)
                events.awaitDiagnostics(source, replacement)
                assertFormattingResponse(otherServer, other, "const other = 1;\n")
                assertTrue("Restart must preserve the shared daemon process", isProcessExecuting(daemon.pid))
                awaitExit("Old primary wrapper/native proxy survived restart", originalProxies)
                formatAndAssert()
            }

            val activePrimaryProxies = processes(root, otherRoot).filter {
                it.command.contains(" lsp-proxy") && it.command.contains(root.path)
            }
            assertEquals(if (nativePrimary) 1 else 2, activePrimaryProxies.size)
            project.service<BiomeServerService>().stopBiomeServer()
            awaitExit("Primary proxies survived stop", activePrimaryProxies)
            assertTrue("The second client still needs the shared daemon", isProcessExecuting(daemon.pid))
            assertEquals(listOf(otherServer), otherManager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList())
            assertEquals(LspServerState.Running, otherServer.state)
            assertEquals(biomeVersion, otherServer.initializeResult?.serverInfo?.version)
            assertFormattingResponse(otherServer, other, "const other = 1;\n")

            val otherProxies = processes(root, otherRoot).filter { it.command.contains(" lsp-proxy") }
            assertEquals("Only the second Node wrapper/native proxy should remain", 2, otherProxies.size)
            otherProject.service<BiomeServerService>().stopBiomeServer()
            awaitExit("Final client proxies or daemon survived disconnect", otherProxies + daemon)

        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var failure = primaryFailure
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (cleanupFailure: Throwable) {
                    if (failure == null) failure = cleanupFailure
                    else failure!!.addSuppressed(cleanupFailure)
                }
            }
            cleanup { com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).closeAllFiles() }
            cleanup { project.service<BiomeServerService>().stopBiomeServer() }
            cleanup { otherFixture.project.service<BiomeServerService>().stopBiomeServer() }
            // Failure cleanup runs after every lifecycle assertion and only touches this
            // fixture's temporary projects. It cannot turn a failed assertion into a pass.
            if (primaryFailure != null) {
                var remaining = emptyList<ProcessSnapshot>()
                cleanup { remaining = processes(*listOfNotNull(root, otherRoot).toTypedArray()) }
                remaining.forEach { process -> cleanup {
                    ProcessHandle.of(process.pid).ifPresent { if (isProcessExecuting(process.pid)) it.destroyForcibly() }
                } }
                cleanup { awaitExit("Failure cleanup did not stop owned processes", remaining) }
            }
            cleanup { otherFixture.tearDown() }
            if (primaryFailure == null) failure?.let { throw it }

        }
    }

    private data class ProcessSnapshot(val pid: Long, val parent: Long, val command: String)

    private fun processes(vararg roots: VirtualFile): List<ProcessSnapshot> = ProcessHandle.allProcesses().use { all ->
        all.map { process ->
            ProcessSnapshot(process.pid(), process.parent().map { it.pid() }.orElse(-1), process.info().commandLine().orElse(""))
        }.filter { process -> roots.any { process.command.contains(it.path) } }.toList()
    }

    private fun logProcesses(label: String, processes: List<ProcessSnapshot>) {
        processes.forEach { println("$label pid=${it.pid} parent=${it.parent} command=${it.command}") }
    }

    private fun awaitExit(message: String, processes: List<ProcessSnapshot>) {
        PlatformTestUtil.waitWithEventsDispatching(message, { processes.none { isProcessExecuting(it.pid) } }, 15)
    }

    private fun awaitInitialReadiness(file: VirtualFile, fileProject: com.intellij.openapi.project.Project,
        fixture: com.intellij.testFramework.fixtures.CodeInsightTestFixture, runtimeEvents: RuntimeGateEvents) {
        try {
            runtimeEvents.awaitDiagnostics(file, timeout = 5)
        } catch (_: AssertionError) {
            FileEditorManager.getInstance(fileProject).closeFile(file)
            fixture.configureFromExistingVirtualFile(file)
            waitUntilFileOpenedByLspServer(fileProject, file, timeout = 20)
            runtimeEvents.awaitDiagnostics(file)
        }
    }

    private fun assertFormattingResponse(server: LspServer, file: VirtualFile, expected: String) {
        val params = org.eclipse.lsp4j.DocumentFormattingParams(server.getDocumentIdentifier(file), org.eclipse.lsp4j.FormattingOptions(2, true))
        val input = VfsUtil.loadText(file)
        val document = com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(input)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val operation = scope.async { withTimeout(15_000) {
            server.sendRequest { it.textDocumentService.formatting(params) }
        } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Other project formatting timed out", { operation.isCompleted }, 20)
            val edits = runBlocking { operation.await() } ?: error("Other project returned no formatting edits")
            val output = StringBuilder(input)
            edits.map { edit ->
                Triple(document.getLineStartOffset(edit.range.start.line) + edit.range.start.character,
                    document.getLineStartOffset(edit.range.end.line) + edit.range.end.character, edit.newText)
            }.sortedByDescending { it.first }.forEach { (start, end, text) -> output.replace(start, end, text) }
            assertEquals(expected, output.toString())
        } finally { scope.cancel() }
    }

    private fun formatAndAssert(expected: String = "const message = 'hello';\n") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val operation = scope.async { withTimeout(15_000) { project.service<BiomeServerService>().format(myFixture.editor.document) } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Formatting timed out", { operation.isCompleted }, 20)
            runBlocking { operation.await() }
            assertEquals(expected, myFixture.editor.document.text)
        } finally { scope.cancel() }
    }

    private fun servers() = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
}
