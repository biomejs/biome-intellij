package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.services.BiomeOnTypeFormattingService
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.BiomeSettingsState
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.*
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import kotlinx.coroutines.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

class BiomeOnTypeFormattingTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private lateinit var scope: CoroutineScope
    private lateinit var file: VirtualFile
    private lateinit var server: TestServer
    private val running = mutableListOf<LspServer>()
    private val jobs = mutableListOf<Job>()
    private val settings get() = BiomeSettings.getInstance(project)
    private val document get() = myFixture.editor.document

    override fun setUp() {
        super.setUp()
        settings.loadState(BiomeSettingsState().apply { formatOnType = true })
        project.service<BiomeLspLifecycleService>().restartAll()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        file = myFixture.addFileToProject("index.js", INPUT).virtualFile
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.editor.caretModel.moveToOffset(12)
        server = TestServer(BiomeLspServerDescriptor(project, file.parent, "/not-launched", file.parent.path, 0))
        running.add(server)
    }

    override fun tearDown() {
        try {
            document.setReadOnly(false)
            scope.cancel()
            jobs.forEach(::await)
            settings.loadState(BiomeSettingsState())
            project.service<BiomeLspLifecycleService>().restartAll()
        } finally { super.tearDown() }
    }

    fun testAdvertisedTriggerUsesPartialEditAndPreservesCaretUndoAndSibling() {
        var received: DocumentOnTypeFormattingParams? = null
        val service = service { selected, params ->
            assertSame(server, selected)
            received = params
            edits()
        }
        await(start(service))
        assertEquals(";", received!!.ch)
        assertEquals(Position(0, 12), received!!.position)
        assertEquals(server.getDocumentIdentifier(file), received!!.textDocument)
        assertEquals(OUTPUT, document.text)
        assertEquals(14, myFixture.editor.caretModel.offset)
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
        assertEquals(INPUT, document.text)
        myFixture.type('x')
        assertTrue(document.text.contains(";x"))
    }

    fun testDefaultOffAndNonTriggersDoNotRequest() {
        val service = service { _, _ -> fail("No on-type request expected"); null }
        settings.formatOnType = false
        service.onTyped(myFixture.editor, ';')?.let(::await)
        settings.formatOnType = true
        service.onTyped(myFixture.editor, 'x')?.let(::await)
        assertEquals(INPUT, document.text)
    }

    fun testDisabledPluginRetainsPreferenceWithoutRequesting() {
        settings.configurationMode = ConfigurationMode.DISABLED
        service { _, _ -> fail("Disabled integration must not request"); null }
            .onTyped(myFixture.editor, ';')?.let(::await)
        assertTrue(settings.state.formatOnType)
        assertFalse(settings.formatOnType)
    }

    fun testPendingResponseAfterOnTypeDisabledIsDiscarded() = pending { settings.formatOnType = false }
    fun testPendingResponseAfterPluginDisabledIsDiscarded() = pending { settings.configurationMode = ConfigurationMode.DISABLED }
    fun testPendingResponseAfterSettingsApplyDisablesTypingIsDiscarded() = pending {
        val configurable = com.github.biomejs.intellijbiome.settings.BiomeConfigurable(project)
        try {
            configurable.createComponent()
            configurable.reset()
            configurable.formatOnTypeCheckBox.isSelected = false
            configurable.apply()
        } finally { configurable.disposeUIResources() }
        assertFalse(settings.formatOnType)
    }
    fun testPendingResponseAfterProjectScopeCancelledIsDiscarded() = pending { scope.cancel() }
    fun testPendingResponseAfterStopIsDiscarded() = pending { project.service<BiomeServerService>().stopBiomeServer() }
    fun testPendingResponseAfterRestartIsDiscarded() = pending { project.service<BiomeLspLifecycleService>().restartAll() }
    fun testPendingResponseAfterSameRootReplacementIsDiscarded() = pending {
        running.clear()
        running.add(TestServer(BiomeLspServerDescriptor(project, file.parent, "/replacement", file.parent.path, 0)))
    }
    fun testPendingResponseAfterShutdownIsDiscarded() = pending { server.currentState = LspServerState.ShutdownNormally }
    fun testPendingResponseAfterDocumentReadOnlyIsDiscarded() = pending { document.setReadOnly(true) }
    fun testPendingResponseAfterCaretMovedIsDiscarded() = pending { myFixture.editor.caretModel.moveToOffset(0) }
    fun testPendingResponseAfterDocumentChangeIsDiscarded() = pending {
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// newer\n") }
    }
    fun testPendingResponseAfterRootStopIntentIsDiscarded() = pending {
        project.service<BiomeLspLifecycleService>().stop(listOf(file.parent))
    }

    fun testPendingResponseAfterFileRenamedIsDiscarded() = pending {
        com.intellij.openapi.application.WriteAction.run<RuntimeException> { file.rename(this, "renamed.js") }
    }

    fun testPendingResponseAfterFileReadOnlyIsDiscarded() {
        try { pending { com.intellij.openapi.application.WriteAction.run<RuntimeException> { file.isWritable = false } } }
        finally { com.intellij.openapi.application.WriteAction.run<RuntimeException> { file.isWritable = true } }
    }

    fun testPendingResponseAfterRegistrationRemovedIsDiscarded() {
        registerDynamic()
        pending { server.descriptor.onTypeFormatting.unregister(UnregistrationParams(listOf(Unregistration("typing", METHOD)))) }
    }

    fun testPendingResponseAfterRegistrationChangedIsDiscarded() {
        registerDynamic()
        pending { registerDynamic(")") }
    }

    fun testStopIntentBeforeTypingDoesNotRequestEvenIfSdkStillRunning() {
        project.service<BiomeLspLifecycleService>().stop(listOf(file.parent))
        service { _, _ -> fail("Stop intent must prevent requests"); null }.onTyped(myFixture.editor, ';')?.let(::await)
    }

    fun testDelegateRecordsRegistrationOnlyAfterSdkAcknowledgment() {
        server.initializeResult.capabilities.documentOnTypeFormattingProvider = null
        val completion = CompletableFuture<Void>()
        val client = client(completion)
        val returned = client.registerCapability(registration())
        assertFalse(returned.isDone)
        assertNull(server.descriptor.onTypeFormatting.select(null, ';'))
        completion.complete(null)
        returned.join()
        await(start(service { _, _ -> edits() }))
        assertEquals(OUTPUT, document.text)
    }

    fun testFailedOrRetiredDelegateCompletionCannotEnableFormatting() {
        val failed = CompletableFuture<Void>()
        val result = client(failed).registerCapability(registration())
        failed.completeExceptionally(IllegalStateException("controlled rejection"))
        assertTrue(result.isCompletedExceptionally)
        assertNull(server.descriptor.onTypeFormatting.select(null, ';'))
        val late = CompletableFuture<Void>()
        val retired = client(late).registerCapability(registration())
        server.descriptor.lspServerListener.serverStopped(true)
        late.complete(null)
        retired.join()
        assertNull(server.descriptor.onTypeFormatting.select(null, ';'))
    }

    fun testUnregistrationDelegateWaitsForSdkAcknowledgment() {
        registerDynamic()
        val completion = CompletableFuture<Void>()
        val result = client(completion).unregisterCapability(UnregistrationParams(listOf(Unregistration("typing", METHOD))))
        assertNotNull(server.descriptor.onTypeFormatting.select(null, ';'))
        completion.complete(null)
        result.join()
        assertNull(server.descriptor.onTypeFormatting.select(null, ';'))
    }

    fun testReentrantStopAfterResponseBeforeWriteBodyRejectsEdit() = reentrant {
        project.service<BiomeServerService>().stopBiomeServer()
    }

    fun testReentrantDisableAfterResponseBeforeWriteBodyRejectsEdit() = reentrant { settings.formatOnType = false }

    fun testClosedEditorRejectsPendingResponse() {
        val editor = EditorFactory.getInstance().createEditor(document, project)
        editor.caretModel.moveToOffset(12)
        try {
            pending(editor) { EditorFactory.getInstance().releaseEditor(editor) }
        } finally {
            if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testViewerAndReadOnlyDocumentNeverRequest() {
        val service = service { _, _ -> fail("Read-only editor must not request"); null }
        val viewer = EditorFactory.getInstance().createViewer(document, project)
        try { service.onTyped(viewer, ';')?.let(::await) }
        finally { EditorFactory.getInstance().releaseEditor(viewer) }
        document.setReadOnly(true)
        service.onTyped(myFixture.editor, ';')?.let(::await)
    }

    fun testSelectionsAndMultipleCaretsNeverRequest() {
        val service = service { _, _ -> fail("Ambiguous caret context must not request"); null }
        myFixture.editor.selectionModel.setSelection(0, 1)
        service.onTyped(myFixture.editor, ';')?.let(::await)
        myFixture.editor.selectionModel.removeSelection()
        myFixture.editor.caretModel.addCaret(com.intellij.openapi.editor.VisualPosition(1, 0))
        service.onTyped(myFixture.editor, ';')?.let(::await)
    }

    fun testInvalidEditSetDoesNotPartiallyChangeDocument() {
        await(start(service { _, _ -> edits() + TextEdit(Range(Position(99, 0), Position(99, 1)), "bad") }))
        assertEquals(INPUT, document.text)
    }

    private fun pending(editor: Editor = myFixture.editor, invalidate: () -> Unit) {
        val received = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service { _, _ ->
            received.complete(Unit)
            // Final validity must hold even when a transport ignores cancellation.
            withContext(NonCancellable) { withTimeout(5_000) { release.await() } }
            edits()
        }
        val operation = start(service, editor)
        try {
            PlatformTestUtil.waitWithEventsDispatching("On-type request did not begin", { received.isCompleted }, 5)
            invalidate()
            val expected = document.text
            release.complete(Unit)
            await(operation)
            assertEquals(expected, document.text)
        } finally { release.complete(Unit) }
    }

    private fun reentrant(invalidate: () -> Unit) {
        var invoked = 0
        val connection = ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
        connection.subscribe(CommandListener.TOPIC, object : CommandListener {
            override fun commandStarted(event: CommandEvent) {
                if (event.project !== project || event.commandName != BiomeBundle.message("biome.format.on.type.command")) return
                connection.disconnect()
                invoked++
                invalidate()
            }
        })
        try {
            await(start(service { _, _ -> edits() }))
            assertEquals("The test must reach the returned-response command boundary", 1, invoked)
            assertEquals(INPUT, document.text)
        } finally { connection.disconnect() }
    }

    private fun service(request: suspend (LspServer, DocumentOnTypeFormattingParams) -> List<TextEdit>?) =
        BiomeOnTypeFormattingService(project, scope, { running.toList() }, request)

    private fun registration(character: String = ";") = RegistrationParams(listOf(Registration("typing", METHOD,
        com.google.gson.JsonParser.parseString("""{"firstTriggerCharacter":"$character","documentSelector":null}"""))))

    private fun registerDynamic(character: String = ";") = server.descriptor.onTypeFormatting.register(registration(character))

    private fun client(completion: CompletableFuture<Void>): Lsp4jClient {
        val handler = java.lang.reflect.Proxy.newProxyInstance(LspServerNotificationsHandler::class.java.classLoader,
            arrayOf(LspServerNotificationsHandler::class.java)) { _, method, _ ->
            check(method.name == "registerCapability" || method.name == "unregisterCapability")
            completion
        } as LspServerNotificationsHandler
        return server.descriptor.createLsp4jClient(handler)
    }

    private fun start(service: BiomeOnTypeFormattingService, editor: Editor = myFixture.editor): Job =
        checkNotNull(service.onTyped(editor, ';')) { "Eligible typing must schedule formatting" }.also { jobs.add(it) }

    private fun await(job: Job) = PlatformTestUtil.waitWithEventsDispatching("On-type operation did not finish", { job.isCompleted }, 10)
    private fun edits() = listOf(TextEdit(Range(Position(0, 0), Position(0, 12)), "let value = 1;"))

    private inner class TestServer(override val descriptor: BiomeLspServerDescriptor) : LspServer {
        var currentState = LspServerState.Running
        override val project get() = this@BiomeOnTypeFormattingTest.project
        override val providerClass = BiomeLspServerSupportProvider::class.java
        override val state get() = currentState
        override val initializeResult = InitializeResult(ServerCapabilities().apply {
            documentOnTypeFormattingProvider = DocumentOnTypeFormattingOptions(";")
        })
        override fun sendNotification(lsp4jSender: (LanguageServer) -> Unit) = error("Unexpected notification")
        override suspend fun <T> sendRequest(lsp4jSender: (LanguageServer) -> CompletableFuture<T>): T? = error("Use injected request")
        override fun <T> sendRequestSync(timeoutMs: Int, lsp4jSender: (LanguageServer) -> CompletableFuture<T>): T? = error("No synchronous request")
        override fun getDocumentIdentifier(file: VirtualFile) = TextDocumentIdentifier(descriptor.getFileUri(file))
        override fun getDocumentVersion(document: Document) = 0
    }

    companion object {
        private const val INPUT = "let value=1;\nconst sibling=  2;\n"
        private const val OUTPUT = "let value = 1;\nconst sibling=  2;\n"
        private const val METHOD = "textDocument/onTypeFormatting"
    }
}
