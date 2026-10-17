package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.actions.BiomeApplySafeFixesAction
import com.github.biomejs.intellijbiome.actions.BiomeSortImportAction
import com.github.biomejs.intellijbiome.services.BiomeLspRequests
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.services.DefaultBiomeLspRequests
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeProjectViewActionsTest : BiomeLspFixtureTestCase() {
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        setUpLspFixture("save-actions")
        val executable = Path.of(myFixture.tempDirPath, "node_modules/.bin/biome")
        check(Files.isRegularFile(executable))
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = executable.toString()
        }
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "Biome") notifications += notification
            }
        })
    }

    fun testClosedSelectedFileAppliesSafeFixesAndUndo() {
        assertClosedFileAction(BiomeApplySafeFixesAction(),
            "let value=1;console.log(value);\n", "const value = 1;\nconsole.log(value);\n")
    }

    fun testClosedSelectedFileSortsImportsAndUndo() {
        assertClosedFileAction(BiomeSortImportAction(),
            "import { z } from \"./z\";\nimport { a } from \"./a\";\nconsole.log(a,z);\n",
            "import { a } from \"./a\";\nimport { z } from \"./z\";\n\nconsole.log(a,z);\n")
    }

    fun testColdActionWaitsWhileDiagnosticsPublicationIsHeld() {
        val file = myFixture.addFileToProject("selected.js", "let value=1;console.log(value);\n").virtualFile
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
        val publicationEntered = AtomicBoolean()
        val publicationRelease = CountDownLatch(1)
        val requested = AtomicBoolean()
        project.messageBus.connect(testRootDisposable).subscribe(BiomeLspDocumentListener.TOPIC, object : BiomeLspDocumentListener {
            override fun diagnosticsPublished(descriptor: BiomeLspServerDescriptor, params: PublishDiagnosticsParams) {
                if (params.uri == descriptor.getFileUri(file)) {
                    publicationEntered.set(true)
                    check(publicationRelease.await(15, TimeUnit.SECONDS)) { "Diagnostic publication was not released" }
                }
            }
        })
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>>? {
                    check(publicationRelease.count == 0L) { "Code actions bypassed pending diagnostic publication" }
                    requested.set(true)
                    return DefaultBiomeLspRequests.codeActions(server, params)
                }
            }), testRootDisposable)
        try {
            start(BiomeApplySafeFixesAction(), file)
            PlatformTestUtil.waitWithEventsDispatching("Initial diagnostic publication did not arrive", { publicationEntered.get() }, 10)
            assertFalse("Cold actions must wait for diagnostic publication", requested.get())
            assertTrue("Pending readiness must not report completion", notifications.isEmpty())
            assertEquals("let value=1;console.log(value);\n", document.text)
        } finally {
            publicationRelease.countDown()
        }
        awaitResult()
        assertOutcome("Safe fixes applied")
        assertTrue(requested.get())
        assertEquals("const value = 1;\nconsole.log(value);\n", document.text)
    }

    private fun assertClosedFileAction(action: AnAction, input: String, expected: String) {
        val file = myFixture.addFileToProject("selected.js", input).virtualFile
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
        val editors = FileEditorManager.getInstance(project)
        assertFalse(editors.isFileOpen(file))
        val events = RuntimeGateEvents(project, testRootDisposable)
        requireDiagnosticsBeforeRequests(file)
        invoke(action, file)
        assertOutcome(if (action is BiomeApplySafeFixesAction) "Safe fixes applied" else "Imports sorted")
        assertEquals(expected, document.text)
        assertTrue("Project View actions open the selected file for LSP synchronization", editors.isFileOpen(file))
        assertSame(document, FileDocumentManager.getInstance().getDocument(file))
        events.awaitDiagnostics(file, "2.5.15")
        val editor = editors.getEditors(file).filterIsInstance<TextEditor>().single()
        UndoManager.getInstance(project).undo(editor)
        assertEquals(input, document.text)
    }

    fun testEmptyDiagnosticsAllowNoOpActionsAndAlreadyOpenReplay() {
        writeConfiguration("""{"linter":{"enabled":false},"assist":{"enabled":false}}""")
        val file = myFixture.addFileToProject("selected.js", "let value=1;console.log(value);\n").virtualFile
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
        requireDiagnosticsBeforeRequests(file)
        for (action in listOf(BiomeApplySafeFixesAction(), BiomeSortImportAction())) {
            invoke(action, file)
            assertOutcome("No changes needed")
            assertEquals("let value=1;console.log(value);\n", document.text)
        }
    }

    fun testAssistOnlyFileSortsImportsAfterInitialDiagnostics() {
        writeConfiguration("""{"linter":{"enabled":false},"assist":{"enabled":true,"actions":{"source":{"organizeImports":"on"}}}}""")
        assertClosedFileAction(BiomeSortImportAction(),
            "import { z } from \"./z\";\nimport { a } from \"./a\";\nconsole.log(a,z);\n",
            "import { a } from \"./a\";\nimport { z } from \"./z\";\n\nconsole.log(a,z);\n")
    }

    fun testIgnoredFileReadinessIsBoundedWithoutRequestingOrEditing() {
        writeConfiguration("""{"files":{"includes":["**","!!ignored"]}}""")
        val events = RuntimeGateEvents(project, testRootDisposable)
        openFirstFile()
        events.awaitDiagnostics(checkNotNull(myFixture.findFileInTempDir("first.js")), "2.5.15")
        val file = myFixture.addFileToProject("ignored/selected.js", "let value=1;console.log(value);\n").virtualFile
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
        val requested = AtomicBoolean()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>>? {
                    requested.set(true)
                    return DefaultBiomeLspRequests.codeActions(server, params)
                }
            }), testRootDisposable)
        invoke(BiomeApplySafeFixesAction(), file)
        assertOutcome("Biome action timed out")
        assertFalse("An ignored document never reaches server readiness", requested.get())
        assertEquals("let value=1;console.log(value);\n", document.text)
    }

    fun testProjectViewSelectionWinsOverUnrelatedActiveEditor() {
        val active = myFixture.addFileToProject("active.js", "let active=1;console.log(active);\n").virtualFile
        myFixture.configureFromExistingVirtualFile(active)
        waitUntilFileOpenedByLspServer(project, active, BiomeLspServerDescriptor::class.java.name)
        val activeEditor = myFixture.editor
        val activeText = activeEditor.document.text
        val cases = listOf(
            Triple(BiomeApplySafeFixesAction(), "let value=1;console.log(value);\n", "const value = 1;\nconsole.log(value);\n"),
            Triple(BiomeSortImportAction(), "import { z } from \"./z\";\nimport { a } from \"./a\";\nconsole.log(a,z);\n",
                "import { a } from \"./a\";\nimport { z } from \"./z\";\n\nconsole.log(a,z);\n"),
        )
        for ((index, case) in cases.withIndex()) {
            val (action, input, expected) = case
            val file = myFixture.addFileToProject("selected$index.js", input).virtualFile
            val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
            invoke(action, file, activeEditor)
            assertOutcome(if (action is BiomeApplySafeFixesAction) "Safe fixes applied" else "Imports sorted")
            assertEquals(expected, document.text)
            assertEquals("The unrelated editor must stay unchanged", activeText, activeEditor.document.text)
            UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<TextEditor>().single())
            assertEquals(input, document.text)
            assertEquals(activeText, activeEditor.document.text)
        }
    }

    fun testStoppedRootIsNotReopenedByProjectViewAction() {
        openFirstFile()
        BiomeServerService.getInstance(project).stopBiomeServer()
        val editors = FileEditorManager.getInstance(project)
        editors.openFiles.forEach(editors::closeFile)
        val file = myFixture.addFileToProject("selected.js", "let value=1;console.log(value);\n").virtualFile
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
        for (action in listOf(BiomeApplySafeFixesAction(), BiomeSortImportAction())) {
            invoke(action, file)
            assertEquals("let value=1;console.log(value);\n", document.text)
            assertFalse("An action must not revive a deliberately stopped root by opening an editor", editors.isFileOpen(file))
            assertEquals("Biome unavailable", notifications.single().title)
            assertNoRunningServer()
        }
    }

    fun testStopDuringSelectedFileOpenPreventsEditsAndRestart() {
        openFirstFile()
        val file = myFixture.addFileToProject("selected.js", "let value=1;console.log(value);\n").virtualFile
        var stopped = false
        project.messageBus.connect(testRootDisposable).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, opened: VirtualFile) {
                    if (opened == file) {
                        stopped = true
                        BiomeServerService.getInstance(project).stopBiomeServer()
                    }
                }
            })
        invoke(BiomeApplySafeFixesAction(), file)
        assertTrue("The selected file must reach the public editor-open boundary", stopped)
        assertEquals("let value=1;console.log(value);\n", checkNotNull(FileDocumentManager.getInstance().getDocument(file)).text)
        assertEquals("Biome unavailable", notifications.single().title)
        assertNoRunningServer()
    }

    fun testSelectedFileRejectsStaleResponse() {
        val file = myFixture.addFileToProject("selected.js", "let value=1;console.log(value);\n").virtualFile
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>> {
                    withContext(Dispatchers.EDT) {
                        WriteCommandAction.runWriteCommandAction(project) { document.setText("// new typing\n") }
                    }
                    return listOf(Either.forRight(CodeAction("stale selected-file edit").apply {
                        edit = WorkspaceEdit(mapOf(params.textDocument.uri to listOf(
                            TextEdit(Range(Position(0, 0), Position(0, 0)), "// obsolete edit\n"))))
                    }))
                }
            }), testRootDisposable)
        invoke(BiomeApplySafeFixesAction(), file)
        assertEquals("// new typing\n", document.text)
        assertEquals("Biome result discarded", notifications.single().title)
    }

    private fun openFirstFile() {
        val file = checkNotNull(myFixture.findFileInTempDir("first.js"))
        myFixture.configureFromExistingVirtualFile(file)
        waitUntilFileOpenedByLspServer(project, file, BiomeLspServerDescriptor::class.java.name)
    }

    private fun requireDiagnosticsBeforeRequests(file: VirtualFile) {
        val diagnosed = AtomicBoolean()
        project.messageBus.connect(testRootDisposable).subscribe(BiomeLspDocumentListener.TOPIC, object : BiomeLspDocumentListener {
            override fun diagnosticsPublished(descriptor: BiomeLspServerDescriptor, params: PublishDiagnosticsParams) {
                if (params.uri == descriptor.getFileUri(file)) diagnosed.set(true)
            }
        })
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>>? {
                    check(diagnosed.get()) { "Code actions reached the server before initial diagnostics for ${file.name}" }
                    return DefaultBiomeLspRequests.codeActions(server, params)
                }
            }), testRootDisposable)
    }

    private fun writeConfiguration(content: String) {
        val config = checkNotNull(myFixture.findFileInTempDir("biome.json"))
        WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(config, content) }
    }

    private fun assertOutcome(title: String) {
        assertEquals("Action outcome: ${notifications.map { "${it.title}: ${it.content}" }}", title,
            notifications.singleOrNull()?.title)
    }

    private fun assertNoRunningServer() {
        assertFalse(LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
            .any { it.state == LspServerState.Running || it.state == LspServerState.Initializing })
    }

    private fun invoke(action: AnAction, file: VirtualFile, editor: Editor? = null) {
        start(action, file, editor)
        awaitResult()
    }

    private fun start(action: AnAction, file: VirtualFile, editor: Editor? = null) {
        notifications.clear()
        val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.PROJECT_VIEW_POPUP, DataContext { key ->
            when (key) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.VIRTUAL_FILE.name -> file
                CommonDataKeys.EDITOR.name -> editor
                else -> null
            }
        })
        action.actionPerformed(event)
    }

    private fun awaitResult() {
        PlatformTestUtil.waitWithEventsDispatching("Project View action did not report its result", { notifications.isNotEmpty() }, 15)
    }
}
