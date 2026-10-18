package com.github.biomejs.intellijbiome.services

import com.github.biomejs.intellijbiome.lsp.BiomeLspLifecycleService
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.lsp.BiomeRageResult
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.BiomeSettingsState
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.*
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.*
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageServer as Lsp4jServer
import java.util.concurrent.CompletableFuture
import java.nio.file.Files

class BiomeTroubleshootingServiceTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private val running = mutableListOf<LspServer>()

    override fun setUp() {
        super.setUp()
        BiomeSettings.getInstance(project).apply {
            loadState(BiomeSettingsState())
            configurationMode = ConfigurationMode.DISABLED
        }
        project.service<BiomeLspLifecycleService>().restartAll()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun tearDown() {
        try {
            scope.cancel()
            project.service<BiomeLspLifecycleService>().restartAll()
        } finally { super.tearDown() }
    }

    fun testFileSelectsOnlyItsOwningRunningServer() {
        val first = myFixture.addFileToProject("first/index.js", "").virtualFile
        val second = myFixture.addFileToProject("second/index.js", "").virtualFile
        val owner = server(second.parent)
        running.addAll(listOf(server(first.parent), owner))
        assertEquals(listOf(owner), service().targets(second).map { it.server })
    }

    fun testNoFileExposesAllChoicesWithoutPickingFirst() {
        val first = myFixture.addFileToProject("first/index.js", "").virtualFile
        val second = myFixture.addFileToProject("second/index.js", "").virtualFile
        running.addAll(listOf(server(first.parent), server(second.parent)))
        assertEquals(2, service().targets(null).size)
        assertTrue(service().targets(myFixture.addFileToProject("outside.js", "").virtualFile).isEmpty())
    }

    fun testInitializingAndStoppedServersAreNotRequested() {
        val file = myFixture.addFileToProject("index.js", "").virtualFile
        for (state in listOf(LspServerState.Initializing, LspServerState.ShutdownNormally, LspServerState.ShutdownUnexpectedly)) {
            running.clear()
            running.add(server(file.parent).apply { currentState = state })
            assertTrue(service().targets(file).isEmpty())
        }
    }

    fun testExplicitStopIntentExcludesAStillRunningServer() {
        val target = target()
        project.service<BiomeLspLifecycleService>().stopAll(target.descriptor.roots.toList())
        assertEquals(LspServerState.Running, target.server.state)
        assertTrue("Stop intent must take effect before the SDK finishes shutdown", service().targets(target.file).isEmpty())
    }

    fun testCapturedExecutableAndRelativeConfigArePreserved() = coroutineTest {
        val directory = Files.createTempDirectory("biome-troubleshooting-selection-")
        Disposer.register(testRootDisposable) { FileUtil.delete(directory.toFile()) }
        val file = checkNotNull(VfsUtil.findFile(Files.writeString(directory.resolve("index.js"), ""), true))
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = "/current/executable"
            configPath = "/current/config.jsonc"
        }
        val descriptor = BiomeLspServerDescriptor(project, file.parent, "/captured/executable", "config/custom.jsonc", 0)
        running.add(TestServer(descriptor))
        val service = service { response() }
        val report = service.collect(service.targets(file).single())!!
        assertTrue(report.text, report.text.contains("Selected executable: /captured/executable"))
        assertTrue(report.text, report.text.contains("Selected configuration: config/custom.jsonc"))
        assertTrue(report.text, report.text.contains("Target configuration: config/custom.jsonc"))
        assertTrue(report.text, report.text.contains("Configured executable: /current/executable"))
        assertTrue(report.text, report.text.contains("Configured configuration: /current/config.jsonc"))
    }

    fun testNoServerPreviewContainsLocalDetailsAndDoesNotDiscoverAnExecutable() {
        val settings = BiomeSettings.getInstance(project)
        settings.executablePath = "/must/not/be/launched"
        settings.state.enableLspFormat = true
        val report = service().localReport(null)
        assertTrue(report.text, report.text.contains("IDE:"))
        assertTrue(report.text, report.text.contains("Plugin:"))
        assertTrue(report.text, report.text.contains("/must/not/be/launched"))
        assertTrue(report.text, report.text.contains("raw=true, effective=false"))
        assertTrue(report.text, report.text.contains("No running Biome server"))
        assertTrue(running.isEmpty())
    }

    fun testLocalAndTargetPathsAndReportedVersionAreLabelled() = coroutineTest {
        val file = myFixture.addFileToProject("index.js", "").virtualFile
        running.add(server(file.parent))
        val service = service { response() }
        val target = readAction { service.targets(file).single() }
        val report = service.collect(target)!!
        assertTrue(report.text, report.text.contains("2.5.15"))
        assertTrue(report.text, report.text.contains("Local file: ${file.path}"))
        assertTrue(report.text, report.text.contains("/target/"))
        assertTrue(report.text, report.text.contains("other active workspaces"))
        assertTrue(report.text, report.text.contains("Version: 2.5.15"))
    }

    fun testTimeoutIsBoundedAndExplained() = coroutineTest {
        val target = target()
        val report = service(timeout = 20) { awaitCancellation() }.collect(target)!!
        assertTrue(report.text, report.text.contains("timed out"))
    }

    fun testNullResponseIsUnavailableRatherThanAnInventedTimeout() = coroutineTest {
        val report = service { null }.collect(target())!!
        assertTrue(report.text, report.text.contains("unavailable"))
        assertFalse(report.text, report.text.contains("timed out"))
    }

    fun testLateResponseAfterStopIsDiscarded() = coroutineTest {
        val target = target()
        val result = service {
            project.service<BiomeLspLifecycleService>().stopAll(target.descriptor.roots.toList())
            response()
        }.collect(target)
        assertNull(result)
    }

    fun testReplacementWithSameRootIsDiscarded() = coroutineTest {
        val target = target()
        val result = service {
            running.clear()
            running.add(server(target.descriptor.roots.single()))
            response()
        }.collect(target)
        assertNull(result)
    }

    fun testOwnershipChangeDuringRequestIsDiscarded() = coroutineTest {
        val target = target()
        val result = service {
            (target.server as TestServer).ownsFile = false
            response()
        }.collect(target)
        assertNull(result)
    }

    fun testResultQueuedBeforeStopIsNotDelivered() = coroutineTest {
        val target = target()
        val service = service { response() }
        val report = service.collect(target)!!
        project.service<BiomeLspLifecycleService>().stopAll(target.descriptor.roots.toList())
        withContext(Dispatchers.EDT) {
            assertFalse(service.deliver(report) { fail("A stale report must not reach the preview") })
        }
    }

    fun testCurrentReportIsDeliveredWithExactPreviewText() = coroutineTest {
        val target = target()
        val service = service { response() }
        val report = service.collect(target)!!
        withContext(Dispatchers.EDT) {
            var shown: String? = null
            assertTrue(service.deliver(report) { shown = it })
            assertEquals(report.text, shown)
        }
    }

    fun testParentCancellationPropagatesAndCancelsOnlyCollection() = coroutineTest {
        val target = target()
        val requested = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val collection = async {
            service {
                requested.complete(Unit)
                try { awaitCancellation() } finally { canceled.complete(Unit) }
            }.collect(target)
        }
        withTimeout(5_000) { requested.await() }
        collection.cancel()
        collection.join()
        withTimeout(5_000) { canceled.await() }
        assertEquals(LspServerState.Running, target.server.state)
        assertEquals(listOf(target.server), running)
    }

    fun testServiceScopeCancellationRejectsPendingCollection() = coroutineTest {
        val target = target()
        val requested = CompletableDeferred<Unit>()
        val collection = async {
            service { requested.complete(Unit); awaitCancellation() }.collect(target)
        }
        withTimeout(5_000) { requested.await() }
        scope.cancel()
        assertTrue(runCatching { collection.await() }.exceptionOrNull() is CancellationException)
    }

    fun testPlatformCancellationIsNotConvertedToFailureText() = coroutineTest {
        val target = target()
        val failure = runCatching { service { throw ProcessCanceledException() }.collect(target) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is ProcessCanceledException || failure is CancellationException)
    }

    fun testRequestOwnedTimeoutRemainsCancellation() = coroutineTest {
        val target = target()
        val failure = runCatching {
            service { withTimeout(20) { awaitCancellation() } }.collect(target)
        }.exceptionOrNull()
        assertTrue(failure.toString(), failure is TimeoutCancellationException)
    }

    fun testLateNonCancellableResponseAfterCallerCancellationIsNotDelivered() = coroutineTest {
        val target = target()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var delivered = false
        val service = service {
            started.complete(Unit)
            withContext(NonCancellable) { withTimeout(5_000) { release.await() } }
            response()
        }
        val collection = async {
            service.collect(target)?.let { report -> withContext(Dispatchers.EDT) { service.deliver(report) { delivered = true } } }
        }
        try {
            withTimeout(5_000) { started.await() }
            collection.cancel()
            release.complete(Unit)
            collection.join()
            assertFalse(delivered)
            assertEquals(LspServerState.Running, target.server.state)
        } finally {
            release.complete(Unit)
        }
    }

    fun testMetadataAlsoRespectsThePreviewBound() {
        BiomeSettings.getInstance(project).configPath = "x".repeat(200_000)
        val text = service().localReport(null).text
        assertTrue(text.length.toString(), text.length <= 65_536)
        assertTrue(text.takeLast(100), text.contains("truncated"))
    }

    private fun coroutineTest(block: suspend CoroutineScope.() -> Unit) {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val operation = testScope.async { block() }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Troubleshooting coroutine did not complete", { operation.isCompleted }, 10)
            runBlocking { operation.await() }
        } finally {
            testScope.cancel()
        }
    }

    private fun service(timeout: Long = 5_000, request: suspend () -> BiomeRageResult? = { error("No RPC expected") }) =
        BiomeTroubleshootingService(project, scope, object : BiomeLspRequests by DefaultBiomeLspRequests {
            override suspend fun rage(server: LspServer) = request()
        }, timeout) { running.toList() }

    private fun target(): BiomeTroubleshootingService.Target {
        val file = myFixture.addFileToProject("index.js", "").virtualFile
        running.add(server(file.parent))
        return service().targets(file).single()
    }

    private fun response() = BiomeRageResult(JsonParser.parseString("""[{"pair":{"name":"Version","value":[{"content":"2.5.15","elements":[]}]}}]"""))

    private fun server(root: VirtualFile): TestServer {
        lateinit var result: TestServer
        result = TestServer(object : LspServerDescriptor(project, "Biome", root) {
            override fun isSupportedFile(file: VirtualFile) = result.ownsFile && VfsUtilCore.isAncestor(root, file, false)
            override fun getFilePath(file: VirtualFile) = "/target/" + file.name
            override fun createCommandLine(): GeneralCommandLine = error("Troubleshooting must not start a process")
        })
        return result
    }

    private inner class TestServer(override val descriptor: LspServerDescriptor) : LspServer {
        var currentState = LspServerState.Running
        var ownsFile = true
        override val providerClass = BiomeLspServerSupportProvider::class.java
        override val project get() = this@BiomeTroubleshootingServiceTest.project
        override val state get() = currentState
        override val initializeResult = InitializeResult(ServerCapabilities(), ServerInfo("biome_lsp", "2.5.15"))
        override fun sendNotification(lsp4jSender: (Lsp4jServer) -> Unit) = error("Unexpected notification")
        override suspend fun <T> sendRequest(lsp4jSender: (Lsp4jServer) -> CompletableFuture<T>): T? = error("Use injected requests")
        override fun <T> sendRequestSync(timeoutMs: Int, lsp4jSender: (Lsp4jServer) -> CompletableFuture<T>): T? = error("Unexpected sync request")
        override fun getDocumentIdentifier(file: VirtualFile) = TextDocumentIdentifier(descriptor.getFileUri(file))
        override fun getDocumentVersion(document: com.intellij.openapi.editor.Document) = 0
    }
}
