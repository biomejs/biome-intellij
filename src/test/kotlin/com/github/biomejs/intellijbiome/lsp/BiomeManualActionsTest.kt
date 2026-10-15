package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.actions.BiomeApplySafeFixesAction
import com.github.biomejs.intellijbiome.actions.BiomeSortImportAction
import com.github.biomejs.intellijbiome.services.BiomeLspRequests
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.services.DefaultBiomeLspRequests
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeManualActionsTest : BiomeLspFixtureTestCase() {
    private val notifications = CopyOnWriteArrayList<Notification>()
    private var requestDisposable: Disposable? = null
    private val actions: List<AnAction> get() = listOf(BiomeApplySafeFixesAction(), BiomeSortImportAction())

    override fun setUp() {
        super.setUp()
        setUpLspFixture("save-actions")
        val executable = Path.of(myFixture.tempDirPath, "node_modules/.bin/biome")
        check(Files.isRegularFile(executable))
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = executable.toString()
        }
        notifications.clear()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "Biome") notifications += notification
            }
        })
    }

    override fun tearDown() {
        try {
            requestDisposable?.let(Disposer::dispose)
            requestDisposable = null
        } finally {
            super.tearDown()
        }
    }

    fun testBothActionsReportMissingServer() {
        openFile()
        BiomeServerService.getInstance(project).stopBiomeServer()
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "Biome unavailable", "language server is not ready")
        }
    }

    fun testBothActionsReportInitializingServerWithoutRequesting() {
        val wrapper = Path.of(myFixture.tempDirPath, "initializing-biome")
        val pid = Path.of(myFixture.tempDirPath, "initializing.pid")
        Files.writeString(wrapper, "#!/bin/sh\nif [ \"\$1\" = '--version' ]; then printf 'Version: 2.2.3\\n'; else printf '%s' \"\$\$\" > '${pid}'; exec sleep 60; fi\n")
        check(wrapper.toFile().setExecutable(true))
        BiomeSettings.getInstance(project).executablePath = wrapper.toString()
        val file = myFixture.findFileInTempDir("first.js")!!
        myFixture.configureFromExistingVirtualFile(file)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Server must be initializing", {
                Files.exists(pid) &&
                    LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
                        .any { it.state == LspServerState.Initializing }
            }, 10)
            var requested = false
            replaceRequests { _, _ -> requested = true; error("Initializing server must not be requested") }
            for (action in actions) {
                val before = myFixture.editor.document.text
                invoke(action)
                assertFalse(requested)
                assertEquals(before, myFixture.editor.document.text)
                assertNotification(NotificationType.WARNING, "Biome unavailable", "language server is not ready")
            }
        } finally {
            BiomeServerService.getInstance(project).stopBiomeServer()
            // This intentionally silent peer cannot answer the SDK's graceful shutdown.
            // Terminate only the process created by this fixture before project teardown.
            if (Files.exists(pid)) {
                ProcessHandle.of(Files.readString(pid).toLong()).ifPresent { child ->
                    child.destroyForcibly()
                    PlatformTestUtil.waitWithEventsDispatching("Initializing fixture must terminate", { !child.isAlive }, 10)
                }
            }
        }
    }

    fun testBothActionsReportNoChanges() {
        openFile()
        replaceRequests { _, _ -> emptyList() }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.INFORMATION, "No changes needed", "first.js")
        }
    }

    fun testBothActionsReportDeclinedWritePreparation() {
        openFile()
        replaceRequests { _, params -> response(params, "// edit requiring write access\n") }
        val disposable = Disposer.newDisposable()
        var preparations = 0
        val outcomes = mutableListOf<Pair<NotificationType, String>>()
        val file = myFixture.file.virtualFile
        ApplicationManager.getApplication().replaceService(
            com.intellij.codeInsight.FileModificationService::class.java,
            object : com.intellij.codeInsight.FileModificationService() {
                override fun preparePsiElementsForWrite(elements: Collection<com.intellij.psi.PsiElement>): Boolean =
                    error("Unexpected PSI preparation")
                override fun prepareFileForWrite(file: com.intellij.psi.PsiFile?): Boolean =
                    error("Unexpected file preparation")
                override fun prepareVirtualFilesForWrite(
                    project: com.intellij.openapi.project.Project,
                    files: Collection<com.intellij.openapi.vfs.VirtualFile>,
                ): Boolean {
                    assertEquals(listOf(file), files.toList())
                    preparations++
                    return false
                }
            },
            disposable,
        )
        try {
            for (action in actions) {
                val before = myFixture.editor.document.text
                invoke(action)
                assertEquals(before, myFixture.editor.document.text)
                assertEquals(1, notifications.size)
                outcomes += notifications.single().let { it.type to it.title }
            }
            assertEquals(2, preparations)
            assertEquals(
                listOf(NotificationType.WARNING to "Biome actions unavailable", NotificationType.WARNING to "Biome actions unavailable"),
                outcomes,
            )
        } finally {
            Disposer.dispose(disposable)
        }
    }


    fun testBothActionsReportIdenticalTextEditAsUnchanged() {
        openFile()
        replaceRequests { _, params -> response(params, "") }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.INFORMATION, "No changes needed", "first.js")
        }
    }

    fun testBothActionsReportDisabledActionsAsUnavailable() {
        openFile()
        replaceRequests { _, params -> disabledResponse(params) }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "Biome actions unavailable", "No changes were applied")
        }
    }

    fun testBothActionsReportCommandEntriesAsUnavailable() {
        openFile()
        replaceRequests { _, _ -> listOf(Either.forLeft(Command("controlled unsupported command", "fixture.command", emptyList()))) }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "Biome actions unavailable", "could not be applied")
        }
    }

    fun testBothActionsReportCommandOnlyCodeActionsAsUnavailable() {
        openFile()
        replaceRequests { _, _ -> listOf(Either.forRight(CodeAction("controlled command action").apply {
            command = Command("controlled command", "fixture.command", emptyList())
        })) }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "Biome actions unavailable", "No changes were applied")
        }
    }

    fun testBothActionsDoNotPartiallyInvokeCommandBearingCodeActions() {
        openFile()
        replaceRequests { _, params -> response(params, "// edit before untracked command\n").onEach {
            it.right.command = Command("controlled command", "fixture.command", emptyList())
        } }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "Biome actions unavailable", "No changes were applied")
        }
    }

    fun testBothActionsReportServerLossDuringRequest() {
        replaceRequests { server, _ ->
            withContext(Dispatchers.EDT) { BiomeServerService.getInstance(project).stopBiomeServer() }
            assertTrue("The request has lost its server", server.state != LspServerState.Running)
            null
        }
        for (action in actions) {
            FileEditorManager.getInstance(project).let { manager -> manager.openFiles.forEach(manager::closeFile) }
            openFile()
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "Biome unavailable", "language server is not ready")
        }
    }

    fun testBothActionsTreatNullFromRunningServerAsUnchanged() {
        openFile()
        replaceRequests { server, _ ->
            assertEquals(LspServerState.Running, server.state)
            null
        }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.INFORMATION, "No changes needed", "first.js")
        }
    }

    fun testBothActionsReportUnavailableCodeActions() {
        openFile()
        replaceRequests { _, _ -> listOf(Either.forRight(CodeAction("controlled unavailable action"))) }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "Biome actions unavailable", "could not be applied")
        }
    }

    fun testBothActionsReportAppliedAndSkippedActionsAsPartial() {
        openFile()
        var skippedFirst = false
        replaceRequests { _, params ->
            val applied = response(params, "// applied edit\n")
            val skipped = disabledResponse(params)
            if (skippedFirst) skipped + applied else applied + skipped
        }
        for (action in actions) {
            for (disabledFirst in listOf(false, true)) {
                skippedFirst = disabledFirst
                val before = myFixture.editor.document.text
                invoke(action)
                assertEquals("// applied edit\n$before", myFixture.editor.document.text)
                assertNotification(NotificationType.WARNING, "Some Biome actions were not applied", "Some changes were applied")
                assertTrue(notifications.single().content.contains("other returned actions were unavailable"))
                UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
                assertEquals(before, myFixture.editor.document.text)
            }
        }
    }

    fun testBothActionsReportUnchangedAndSkippedActionsAsUnavailable() {
        openFile()
        var skippedFirst = false
        replaceRequests { _, params ->
            val unchanged = response(params, "")
            val skipped = disabledResponse(params)
            if (skippedFirst) skipped + unchanged else unchanged + skipped
        }
        for (action in actions) {
            for (disabledFirst in listOf(false, true)) {
                skippedFirst = disabledFirst
                val before = myFixture.editor.document.text
                invoke(action)
                assertEquals(before, myFixture.editor.document.text)
                assertNotification(NotificationType.WARNING, "Biome actions unavailable", "No changes were applied")
            }
        }
    }

    fun testBothActionsApplyEditsAndUndo() {
        openFile()
        replaceRequests { _, params -> response(params, "// manual edit\n") }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals("// manual edit\n$before", myFixture.editor.document.text)
            val prefix = if (action is BiomeApplySafeFixesAction) "biome.apply.safe.fixes" else "biome.apply.sort.import"
            assertNotification(NotificationType.INFORMATION, BiomeBundle.message("$prefix.success.label"), BiomeBundle.message("$prefix.success.description"))
            val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
            UndoManager.getInstance(project).undo(editor)
            assertEquals(before, myFixture.editor.document.text)
        }
    }

    fun testBothActionsApplyRealServerEditsAndUndo() {
        // Require the current server to become ready on the first document open.
        val events = RuntimeGateEvents(project, testRootDisposable)
        openFile()
        val file = myFixture.file.virtualFile
        events.awaitDiagnostics(file, "2.5.15")
        val cases = listOf(
            Triple(BiomeApplySafeFixesAction(), "let value=1;console.log(value);\n", "const value = 1;\nconsole.log(value);\n"),
            Triple(BiomeSortImportAction(), "import { z } from \"./z\";\nimport { a } from \"./a\";\nconsole.log(a,z);\n",
                "import { a } from \"./a\";\nimport { z } from \"./z\";\n\nconsole.log(a,z);\n"),
        )
        for ((action, input, expected) in cases) {
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText(input) }
            invoke(action)
            assertEquals(expected, myFixture.editor.document.text)
            val prefix = if (action is BiomeApplySafeFixesAction) "biome.apply.safe.fixes" else "biome.apply.sort.import"
            assertNotification(NotificationType.INFORMATION, BiomeBundle.message("$prefix.success.label"), BiomeBundle.message("$prefix.success.description"))
            UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
            assertEquals(input, myFixture.editor.document.text)
        }
    }

    fun testBothActionsReportStaleResponseAfterTyping() {
        openFile()
        replaceRequests { _, params ->
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("// new typing\n") }
            }
            response(params, "// stale edit\n")
        }
        for (action in actions) {
            // Changing text each time guarantees a distinct modification stamp.
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("// before typing\n") }
            invoke(action)
            assertEquals("// new typing\n", myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "Biome result discarded", "changed")
        }
    }

    fun testBothActionsReportServerFailure() {
        openFile()
        replaceRequests { _, _ -> error("controlled request failure") }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.ERROR, if (action is BiomeApplySafeFixesAction) "Failed to apply safe fixes" else "Failed to sort imports", "controlled request failure")
        }
    }

    fun testBothActionsReportOwnTimeout() {
        openFile()
        replaceRequests { _, _ -> awaitCancellation() }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "Biome action timed out", "five seconds")
        }
    }

    fun testBothActionsPreservePlatformCancellation() {
        openFile()
        replaceRequests { _, _ -> throw ProcessCanceledException() }
        for (action in actions) {
            val before = myFixture.editor.document.text
            val failure = runCatching { invoke(action) }.exceptionOrNull()
            assertTrue("Platform cancellation must leave the action: $failure", failure is ProcessCanceledException || failure is CancellationException)
            assertEquals(before, myFixture.editor.document.text)
            assertTrue("Cancellation must not notify", notifications.isEmpty())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun testBothActionsPreserveParentCancellation() {
        openFile()
        replaceRequests { _, _ ->
            checkNotNull(currentCoroutineContext()[Job]?.parent).cancel(CancellationException("controlled parent cancellation"))
            awaitCancellation()
        }
        for (action in actions) {
            val before = myFixture.editor.document.text
            val failure = runCatching { invoke(action) }.exceptionOrNull()
            assertTrue("Parent cancellation must leave the action: $failure", failure is ProcessCanceledException || failure is CancellationException)
            assertEquals(before, myFixture.editor.document.text)
            assertTrue("Cancellation must not notify", notifications.isEmpty())
        }
    }

    fun testBothActionsPreserveRequestTimeoutCancellation() {
        openFile()
        replaceRequests { _, _ -> withTimeout(20) { awaitCancellation() } }
        for (action in actions) {
            val before = myFixture.editor.document.text
            val failure = runCatching { invoke(action) }.exceptionOrNull()
            assertTrue("Request timeout must remain cancellation: $failure", failure is ProcessCanceledException || failure is CancellationException)
            assertEquals(before, myFixture.editor.document.text)
            assertTrue(notifications.isEmpty())
        }
    }

    private fun openFile() {
        val file = myFixture.findFileInTempDir("first.js")!!
        myFixture.configureFromExistingVirtualFile(file)
        waitUntilFileOpenedByLspServer(project, file, "com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor")
        notifications.clear()
    }

    private fun replaceRequests(request: suspend (LspServer, CodeActionParams) -> List<Either<Command, CodeAction>>?) {
        val disposable = requestDisposable ?: Disposer.newDisposable().also {
            requestDisposable = it
            Disposer.register(testRootDisposable, it)
        }
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams) = request(server, params)
            }), disposable)
    }

    private fun response(params: CodeActionParams, text: String): List<Either<Command, CodeAction>> =
        listOf(Either.forRight(CodeAction("controlled manual edit").apply {
            edit = WorkspaceEdit(mapOf(params.textDocument.uri to listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), text))))
        }))

    private fun disabledResponse(params: CodeActionParams): List<Either<Command, CodeAction>> =
        response(params, "// disabled edit must not be applied\n").onEach {
            it.right.disabled = CodeActionDisabled("controlled unavailable fix")
        }

    private fun event(action: AnAction): AnActionEvent {
        val context = DataContext { key ->
            when (key) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.EDITOR.name -> myFixture.editor
                CommonDataKeys.VIRTUAL_FILE.name -> myFixture.file.virtualFile
                else -> null
            }
        }
        return AnActionEvent.createFromAnAction(action, null, ActionPlaces.EDITOR_POPUP, context)
    }

    private fun invoke(action: AnAction) {
        notifications.clear()
        action.actionPerformed(event(action))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun assertNotification(type: NotificationType, title: String, content: String) {
        assertEquals("One manual action notification", 1, notifications.size)
        val notification = notifications.single()
        assertEquals(type, notification.type)
        assertEquals(title, notification.title)
        assertTrue("Expected '$content' in '${notification.content}'", notification.content.contains(content))
    }
}
