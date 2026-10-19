package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerManagerListener
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Real editor/VFS lifecycle probes: no provider invocation or manual start in recovery cases. */
@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeConfigRecoveryLspTest : BiomeLspFixtureTestCase() {
    private lateinit var root: VirtualFile
    private lateinit var source: VirtualFile
    private val config = """{"javascript":{"formatter":{"quoteStyle":"single"}}}"""

    private lateinit var events: RuntimeGateEvents

    override fun setUp() {
        super.setUp()
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
        events = RuntimeGateEvents(project, testRootDisposable)
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        root = myFixture.tempDirFixture.findOrCreateDir(".")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root)
        source = myFixture.tempDirFixture.createFile("index.js", "const message=\"hello\";\n")
        assertEquals(ConfigurationMode.AUTOMATIC, BiomeSettings.getInstance(project).configurationMode)
        assertNotNull("Automatic package discovery is a prerequisite", BiomePackage(project).binaryPath(source))
    }

    fun testExistingValidConfigStartsAndFormats() {
        externalConfig(config)
        openSource()
        assertFormatting()
    }

    fun testExternalConfigCreationStartsForAlreadyOpenFile() {
        openSource()
        settleWithoutServer()
        externalConfig(config)
        assertFormatting()
    }

    fun testCopiedConfigStartsForAlreadyOpenFile() {
        val draft = myFixture.tempDirFixture.createFile("draft.json", config)
        openSource()
        val editor = myFixture.editor
        settleWithoutServer()
        WriteAction.run<RuntimeException> { draft.copy(this, root, "biome.json") }
        assertFormatting()
        assertSame("Copy recovery must retain the already open editor", editor, myFixture.editor)
    }

    fun testRenamingToJsoncConfigStartsForAlreadyOpenFile() {
        val draft = myFixture.tempDirFixture.createFile("draft.json", config)
        openSource()
        val editor = myFixture.editor
        settleWithoutServer()
        WriteAction.run<RuntimeException> { draft.rename(this, "biome.jsonc") }
        assertFormatting()
        assertSame("Rename recovery must retain the already open editor", editor, myFixture.editor)
    }

    fun testMovingJsoncConfigStartsForAlreadyOpenFile() {
        val draft = myFixture.tempDirFixture.createFile("draft/biome.jsonc", config)
        openSource()
        val editor = myFixture.editor
        settleWithoutServer()
        WriteAction.run<RuntimeException> { draft.move(this, root) }
        assertFormatting()
        assertSame("Move recovery must retain the already open editor", editor, myFixture.editor)
    }

    fun testMalformedConfigRepairStartsForAlreadyOpenFile() {
        externalConfig("{broken")
        openSource()
        settleWithoutServer()
        externalConfig(config)
        assertFormatting()
    }

    fun testReopenAfterExternalConfigCreationIsRecoveryControl() {
        openSource()
        settleWithoutServer()
        externalConfig(config)
        FileEditorManager.getInstance(project).closeFile(source)
        openSource()
        assertFormatting()
    }

    fun testExistingServerObservesConfigEditWithoutRestart() {
        externalConfig(config)
        openSource()
        awaitInitialReadinessWithReopenControl()
        assertFormatting()
        val original = servers().single()
        externalConfig(config.replace("single", "double"))
        val deadline = System.nanoTime() + 2_000_000_000L
        PlatformTestUtil.waitWithEventsDispatching("Config watcher did not settle", { System.nanoTime() >= deadline }, 5)
        assertFormatting("const message = \"hello\";\n")
        assertSame("Ordinary config edits must retain the running server", original, servers().single())
    }

    fun testNativeRescanStartsAfterWriteActionReleases() = verifyNativeRescanStop(stop = false)

    fun testStopAfterNativeRescanDispatchPreventsLateStart() = verifyNativeRescanStop(stop = true)

    private fun verifyNativeRescanStop(stop: Boolean) {
        val dispatcher = QueuedDiscoveryDispatcher()
        val discoveryScope = CoroutineScope(SupervisorJob() + dispatcher)
        com.intellij.openapi.util.Disposer.register(testRootDisposable) {
            discoveryScope.cancel()
            dispatcher.drainCancelledTasks()
        }
        project.replaceService(BiomeConfigDiscoveryService::class.java,
            BiomeConfigDiscoveryService(project, discoveryScope), testRootDisposable)
        openSource()
        settleWithoutServer()
        externalConfig(config)
        // Keep the watcher's task queued. Hold the SDK's read behind a real write action.
        WriteAction.run<RuntimeException> {
            LspServerManager.getInstance(project).startServersIfNeeded(BiomeLspServerSupportProvider::class.java)
            assertTrue("The native discovery read must not run before this write action releases", servers().isEmpty())
            if (stop) project.service<BiomeServerService>().stopBiomeServer()
        }
        if (!stop) {
            assertFormatting()
            return
        }
        val deadline = System.nanoTime() + 2_000_000_000L
        PlatformTestUtil.waitWithEventsDispatching("Native discovery did not settle", { System.nanoTime() >= deadline }, 5)
        assertFormattingUnavailable()
        assertTrue("Stop must leave no newly discovered server", servers().isEmpty())
        project.service<BiomeServerService>().restartBiomeServer()
        assertFormatting()
    }

    fun testReopenedStoppedFileResumesWhenItsConfigBecomesAvailable() {
        openSource()
        settleWithoutServer()
        project.service<BiomeServerService>().stopBiomeServer()
        FileEditorManager.getInstance(project).closeFile(source)
        openSourceWithBeforeEvent()
        val editor = myFixture.editor
        settleWithoutServer()
        externalConfig(config)
        assertFormatting()
        assertSame("Config recovery must retain the explicitly reopened editor", editor, myFixture.editor)
    }

    fun testStopRejectsAnEarlierUnresolvedOpenRequest() {
        openSourceWithBeforeEvent()
        settleWithoutServer()
        project.service<BiomeServerService>().stopBiomeServer()
        externalConfig(config)
        settleWithoutServer()
        assertFormattingUnavailable()
    }

    private fun openSourceWithBeforeEvent() {
        project.service<BiomeLspLifecycleService>()
        val editors = FileEditorManager.getInstance(project)
        // The SDK test manager omits the desktop's Before notification.
        project.messageBus.syncPublisher(com.intellij.openapi.fileEditor.FileEditorManagerListener.Before.FILE_EDITOR_MANAGER)
            .beforeFileOpened(editors, source)
        openSource()
    }

    fun testUnsupportedFileDoesNotStartAfterConfigCreation() {
        source = myFixture.tempDirFixture.createFile("notes.txt", "hello")
        openSource()
        settleWithoutServer()
        externalConfig(config)
        settleWithoutServer()
    }

    fun testRecoveryPreservesUnrelatedRootServer() {
        val otherConfig = myFixture.tempDirFixture.createFile("other/biome.json", config)
        val other = myFixture.tempDirFixture.createFile("other/index.js", "const other=1;\n")
        myFixture.configureFromExistingVirtualFile(other)
        waitUntilFileOpenedByLspServer(project, other, timeout = 15)
        val original = servers().single()
        assertEquals(listOf(otherConfig.parent), original.descriptor.roots.toList())
        root = myFixture.tempDirFixture.findOrCreateDir("pending")
        source = myFixture.tempDirFixture.createFile("pending/index.js", "const message=\"hello\";\n")
        openSource()
        val deadline = System.nanoTime() + 2_000_000_000L
        PlatformTestUtil.waitWithEventsDispatching("Open event did not settle", { System.nanoTime() >= deadline }, 5)
        assertEquals(listOf(original), servers().toList())
        externalConfig(config)
        assertFormatting()
        assertTrue("Unrelated root server must retain identity", servers().any { it === original })
        assertEquals(2, servers().size)
    }

    fun testRecoveryPreservesAnotherProjectServer() {
        openSource()
        settleWithoutServer()
        // Initialize primary-project listeners and highlighting caches before the secondary fixture snapshots them.
        com.intellij.ide.bookmarks.BookmarkManager.getInstance(project)
        com.intellij.refactoring.suggested.SuggestedRefactoringProvider.getInstance(project)
        myFixture.doHighlighting()
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val builder = factory.createFixtureBuilder("${name}-other-project")
        val otherFixture = factory.createCodeInsightFixture(builder.fixture)
        builder.addModule(EmptyModuleFixtureBuilder::class.java).addSourceContentRoot(otherFixture.tempDirPath)
        otherFixture.setUp()
        try {
            val otherProject = otherFixture.project
            configureLocalNodeForRuntimeTests(otherProject, otherFixture.testRootDisposable)
            otherFixture.testDataPath = myFixture.testDataPath
            val otherRoot = otherFixture.tempDirFixture.findOrCreateDir(".")
            TestNpmPackageInstaller(otherFixture).installForTest(javaClass, otherRoot)
            otherFixture.tempDirFixture.createFile("biome.json", config)
            val other = otherFixture.tempDirFixture.createFile("other.js", "const other=1;\n")
            otherFixture.configureFromExistingVirtualFile(other)
            waitUntilFileOpenedByLspServer(otherProject, other, timeout = 15)
            val otherManager = LspServerManager.getInstance(otherProject)
            val original = otherManager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
            assertEquals("2.5.15", original.initializeResult?.serverInfo?.version)
            externalConfig(config)
            waitUntilFileOpenedByLspServer(project, source, timeout = 15)
            events.awaitDiagnostics(source)
            assertEquals("2.5.15", servers().single().initializeResult?.serverInfo?.version)
            assertEquals(listOf(original), otherManager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList())
        } finally {
            // The secondary fixture tracks all threads created while it exists, including the
            // primary project's newly recovered server. Finish both test-owned servers first.
            com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            project.service<BiomeServerService>().stopBiomeServer()
            otherFixture.project.service<BiomeServerService>().stopBiomeServer()
            otherFixture.tearDown()
        }
    }

    fun testProjectDisposalPreventsLateServerStartAfterConfigEvent() {
        com.intellij.ide.bookmarks.BookmarkManager.getInstance(project)
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val builder = factory.createFixtureBuilder("${name}-disposing-project")
        val otherFixture = factory.createCodeInsightFixture(builder.fixture)
        builder.addModule(EmptyModuleFixtureBuilder::class.java).addSourceContentRoot(otherFixture.tempDirPath)
        otherFixture.setUp()
        val otherProject = otherFixture.project
        val lateStarts = AtomicInteger()
        try {
            configureLocalNodeForRuntimeTests(otherProject, otherFixture.testRootDisposable)
            otherFixture.testDataPath = myFixture.testDataPath
            val otherRoot = otherFixture.tempDirFixture.findOrCreateDir(".")
            TestNpmPackageInstaller(otherFixture).installForTest(javaClass, otherRoot)
            val other = otherFixture.tempDirFixture.createFile("other.js", "const other=1;\n")
            LspServerManager.getInstance(otherProject).addLspServerManagerListener(object : LspServerManagerListener {
                override fun serverStateChanged(lspServer: LspServer) {
                    if (otherProject.isDisposed && lspServer.state == LspServerState.Running) lateStarts.incrementAndGet()
                }
            }, testRootDisposable, true)
            otherFixture.configureFromExistingVirtualFile(other)
            PlatformTestUtil.waitWithEventsDispatching("Discovery subscription was not installed", {
                otherProject.getServiceIfCreated(BiomeConfigDiscoveryService::class.java) != null
            }, 10)
            Files.writeString(otherRoot.toNioPath().resolve("biome.json"), config)
            VfsUtil.markDirtyAndRefresh(false, true, true, otherRoot)
        } finally {
            otherFixture.tearDown()
        }
        assertTrue(otherProject.isDisposed)
        val deadline = System.nanoTime() + 2_000_000_000L
        PlatformTestUtil.waitWithEventsDispatching("Disposal callbacks did not settle", { System.nanoTime() >= deadline }, 5)
        assertEquals("No server may reach Running after its project is disposed", 0, lateStarts.get())
    }

    fun testDeleteAndRecreateConfigStartsOnce() {
        externalConfig("{broken")
        openSource()
        settleWithoutServer()
        Files.delete(root.toNioPath().resolve("biome.json"))
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        repeat(3) { externalConfig(config) }
        assertFormatting()
        assertEquals("Config event burst must not create duplicate servers", 1, servers().size)
    }

    fun testRenameConfigStartsForAlreadyOpenFile() {
        openSource()
        settleWithoutServer()
        val draft = root.toNioPath().resolve("draft.json")
        Files.writeString(draft, config)
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        Files.move(draft, root.toNioPath().resolve("biome.json"))
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        assertFormatting()
    }

    fun testStillMalformedConfigDoesNotStart() {
        externalConfig("{broken")
        openSource()
        settleWithoutServer()
        externalConfig("{stillbroken")
        settleWithoutServer()
    }

    fun testDisabledPluginDoesNotStartAfterConfigCreation() {
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.DISABLED
        openSource()
        externalConfig(config)
        settleWithoutServer()
    }

    // Only establishes the precondition for the existing-server edit control. Recovery tests never call this.
    private fun awaitInitialReadinessWithReopenControl() {
        waitUntilFileOpenedByLspServer(project, source, timeout = 15)
        try {
            events.awaitDiagnostics(source, timeout = 5)
        } catch (_: AssertionError) {
            FileEditorManager.getInstance(project).closeFile(source)
            openSource()
            waitUntilFileOpenedByLspServer(project, source, timeout = 15)
            events.awaitDiagnostics(source)
        }
    }

    private fun assertFormattingUnavailable() {
        val document = myFixture.editor.document
        val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val operation = operationScope.async { withTimeout(15_000) { project.service<BiomeServerService>().format(document) } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Formatting did not finish", { operation.isCompleted }, 20)
            assertEquals("Stopped discovery must leave formatting unavailable",
                BiomeServerService.Outcome.Unavailable, runBlocking { operation.await() })
            assertEquals("const message=\"hello\";\n", document.text)
        } finally { operationScope.cancel() }
    }

    private fun externalConfig(text: String) {
        Files.writeString(root.toNioPath().resolve("biome.json"), text)
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
    }

    private fun openSource() { myFixture.configureFromExistingVirtualFile(source) }

    private fun settleWithoutServer() {
        val deadline = System.nanoTime() + 2_000_000_000L
        PlatformTestUtil.waitWithEventsDispatching("Event queue did not settle", { System.nanoTime() >= deadline }, 5)
        assertTrue("No valid config means no Biome server", servers().isEmpty())
    }

    private fun servers() = LspServerManager.getInstance(project)
        .getServersForProvider(BiomeLspServerSupportProvider::class.java)

    private fun assertFormatting(expected: String = "const message = 'hello';\n") {
        val editor = myFixture.editor
        val document = editor.document
        waitUntilFileOpenedByLspServer(project, source, timeout = 15)
        events.awaitDiagnostics(source)
        assertSame(editor, myFixture.editor)
        assertSame(document, myFixture.editor.document)
        assertEquals("2.5.15", servers().single { it.descriptor.isSupportedFile(source) }.initializeResult?.serverInfo?.version)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val operation = scope.async { withTimeout(15_000) { project.service<BiomeServerService>().format(myFixture.editor.document) } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Formatting timed out", { operation.isCompleted }, 20)
            runBlocking { operation.await() }
            assertEquals(expected, myFixture.editor.document.text)
        } finally { scope.cancel() }
    }
}
