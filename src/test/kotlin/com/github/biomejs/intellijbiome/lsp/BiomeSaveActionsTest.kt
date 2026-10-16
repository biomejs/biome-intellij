package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.services.BiomeLspRequests
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.services.DefaultBiomeLspRequests
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.openapi.command.WriteCommandAction
import com.github.biomejs.intellijbiome.actions.BiomeCheckOnSaveAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.*
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicReference
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.lsp.api.LspServer
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.awaitCancellation
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.intellij.testFramework.LoggedErrorProcessor

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeSaveActionsTest : BiomeLspFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        setUpLspFixture("save-actions")
        val executableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "biome.cmd" else "biome"
        val executable = Path.of(myFixture.tempDirPath, "node_modules", ".bin", executableName)
        check(Files.isRegularFile(executable)) { "Locked Biome 2.5.15 fixture executable is required" }
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = executable.toString()
        }
    }

    fun testCurrentServerUsesImmediateActionCapabilities() {
        val document = openCurrentDocument()
        val file = FileDocumentManager.getInstance().getFile(document)!!
        val server = com.intellij.platform.lsp.api.LspServerManager.getInstance(project)
            .getServersForProvider(BiomeLspServerSupportProvider::class.java)
            .single { it.descriptor.isSupportedFile(file) }
        val capabilities = server.descriptor.createInitializeParams().capabilities
        assertFalse("The bounded action path cannot resolve deferred edits",
            capabilities.textDocument.codeAction.resolveSupport?.properties.orEmpty().contains("edit"))
        assertTrue("Workspace configuration must remain enabled", capabilities.workspace.configuration)
        assertNotNull("Native code action kinds must remain available", capabilities.textDocument.codeAction.codeActionLiteralSupport)
        assertEquals("2.5.15", server.initializeResult?.serverInfo?.version)
    }

    fun testCurrentSafeFixesDoNotSortImportsWhenSortingIsDisabled() {
        val document = openCurrentDocument()
        val imports = "import { z } from \"./z\";\nimport { a } from \"./a\";\n"
        val input = imports + "let value=1;console.log(z,a,value);\n"
        WriteCommandAction.runWriteCommandAction(project) { document.setText(input) }
        BiomeSettings.getInstance(project).apply {
            applySafeFixesOnSave = true
            sortImportOnSave = false
            formatOnSave = false
        }
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertTrue("Safe fixes must execute on the first document open", document.text.contains("const value = 1;"))
        assertTrue("Safe fixes alone must retain import order", document.text.startsWith(imports))
        assertEquals(document.text, diskText(document))
    }

    fun testCurrentOrganizeImportsWorksOnFirstOpen() {
        val document = openCurrentDocument()
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("import { z } from \"./z\";\nimport { a } from \"./a\";\nconsole.log(a,z);\n")
        }
        BiomeSettings.getInstance(project).sortImportOnSave = true
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertTrue("Organize imports must execute on the first document open",
            document.text.startsWith("import { a } from \"./a\";\nimport { z } from \"./z\";\n"))
        assertEquals(document.text, diskText(document))
    }

    private fun openCurrentDocument(): Document {
        val events = RuntimeGateEvents(project, testRootDisposable)
        val file = myFixture.findFileInTempDir("first.js") ?: error("Missing fixture first.js")
        myFixture.configureFromExistingVirtualFile(file)
        waitUntilFileOpenedByLspServer(project, file, "com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor")
        events.awaitDiagnostics(file, version = "2.5.15")
        return FileDocumentManager.getInstance().getDocument(file) ?: error("Missing document")
    }

    fun testSaveAllContinuesAfterFileFailure() = checkBatchIsolation { error("controlled request failure") }

    fun testSaveAllContinuesAfterFileTimeout() = checkBatchIsolation { awaitCancellation() }

    fun testTypingDiscardsStaleResponseAndRemainingFeatures() {
        val document = openDocuments().first()
        var formattingRequested = false
        val service = BiomeServerService(project, object : BiomeLspRequests by DefaultBiomeLspRequests {
            override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>> {
                withContext(Dispatchers.EDT) {
                    WriteCommandAction.runWriteCommandAction(project) { document.setText("// newer typing\n") }
                }
                return listOf(Either.forRight(CodeAction("controlled fix").apply {
                    edit = WorkspaceEdit(mapOf(params.textDocument.uri to listOf(
                        TextEdit(Range(Position(0, 0), Position(0, 0)), "// stale response\n"))))
                }))
            }
            override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                formattingRequested = true
                return emptyList()
            }
        })
        runService {
            service.executeFeatures(document, EnumSet.of(BiomeServerService.Feature.ApplySafeFixes, BiomeServerService.Feature.Format))
        }
        assertEquals("// newer typing\n", document.text)
        assertFalse("Stale work must stop later stages", formattingRequested)
    }

    fun testCancellationBeforeWriteDoesNotMutate() {
        val document = openDocuments().first()
        val original = document.text
        val cancellation = CancellationException("cancel before write")
        val service = BiomeServerService(project, object : BiomeLspRequests by DefaultBiomeLspRequests {
            override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                currentCoroutineContext().cancel(cancellation)
                return listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "// canceled\n"))
            }
        })
        var thrown: Throwable? = null
        runService {
            try {
                service.format(document)
            } catch (failure: Throwable) {
                thrown = failure
            }
        }
        assertSame(cancellation, thrown)
        assertEquals(original, document.text)
    }

    fun testCancellationWhileWriteIsQueuedDoesNotMutate() {
        val document = openDocuments().last()
        val file = FileDocumentManager.getInstance().getFile(document)!!
        val original = document.text
        val originalSeparator = file.detectedLineSeparator
        val originalBytes = diskText(document)
        val requestReady = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val writeQueued = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val result = AtomicReference<Result<Unit>>()
        val notifications = CopyOnWriteArrayList<com.intellij.notification.Notification>()
        val warnings = CopyOnWriteArrayList<String>()
        project.messageBus.connect(testRootDisposable).subscribe(com.intellij.notification.Notifications.TOPIC,
            object : com.intellij.notification.Notifications {
                override fun notify(notification: com.intellij.notification.Notification) {
                    if (notification.groupId == "Biome") notifications += notification
                }
            })
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    requestReady.complete(Unit)
                    releaseResponse.await()
                    // This marker cannot run on the single worker until the current
                    // coroutine yields after dispatching its write to the held EDT.
                    executor.execute { writeQueued.countDown() }
                    return listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "// canceled\r\n"))
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        try {
            LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
                override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                    if (category.contains("BiomeCheckOnSaveAction")) warnings += message
                    return false
                }
            }).use {
                scope.launch { result.set(runCatching<Unit> { BiomeServerService.getInstance(project).format(document) }) }
                PlatformTestUtil.waitWithEventsDispatching("Formatting request did not start", { requestReady.isCompleted }, 10)
                releaseResponse.complete(Unit)
                // Do not dispatch EDT events until the write has queued and its
                // parent is canceled. A request-boundary check alone cannot pass.
                assertTrue("Write was not queued", writeQueued.await(10, TimeUnit.SECONDS))
                assertNull("The write must still be waiting for EDT", result.get())
                val cancellation = CancellationException("cancel queued write")
                scope.cancel(cancellation)
                PlatformTestUtil.waitWithEventsDispatching("Canceled write did not finish", { result.get() != null }, 10)
                val failure = result.get().exceptionOrNull()
                assertTrue(failure === cancellation || failure?.cause === cancellation)
                assertEquals(original, document.text)
                assertEquals(originalSeparator, file.detectedLineSeparator)
                assertEquals(originalBytes, diskText(document))
                assertTrue(warnings.isEmpty())
                assertTrue(notifications.isEmpty())
            }
        } finally {
            releaseResponse.complete(Unit)
            scope.cancel()
            dispatcher.close()
        }
    }

    fun testCommandBearingActionDoesNotApplyItsEdit() {
        val document = openDocuments().first()
        val original = document.text
        val service = BiomeServerService(project, object : BiomeLspRequests by DefaultBiomeLspRequests {
            override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>> =
                listOf(Either.forRight(CodeAction("edit and command").apply {
                    edit = WorkspaceEdit(mapOf(params.textDocument.uri to listOf(
                        TextEdit(Range(Position(0, 0), Position(0, 0)), "// partial action\n"))))
                    command = Command("untracked command", "fixture.command", emptyList())
                }))
        })
        runService { service.applySafeFixes(document) }
        assertEquals("An action with untracked completion must not be partially applied", original, document.text)
    }

    private fun runService(operation: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val result = AtomicReference<Result<Unit>>()
        try {
            scope.launch { result.set(runCatching { operation() }) }
            PlatformTestUtil.waitWithEventsDispatching("Service operation did not finish", { result.get() != null }, 15)
            result.get().getOrThrow()
        } finally {
            scope.cancel()
        }
    }

    fun testEnabledFeaturesRunInOrderAndPersist() {
        val document = openDocuments().last()
        val calls = CopyOnWriteArrayList<String>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>>? {
                    assertEquals(org.eclipse.lsp4j.CodeActionTriggerKind.Automatic, params.context.triggerKind)
                    assertEquals(Position(0, 0), params.range.start)
                    calls += params.context.only.single()
                    return DefaultBiomeLspRequests.codeActions(server, params)
                }
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit>? {
                    calls += "format"
                    return DefaultBiomeLspRequests.formatting(server, params)
                }
            }), testRootDisposable)
        val settings = BiomeSettings.getInstance(project)
        val input = "let value=1;console.log(value);\n"
        val subsets = listOf(
            Triple(true, false, false), Triple(false, true, false), Triple(false, false, true), Triple(true, true, true),
        )
        for ((fixes, imports, format) in subsets) {
            calls.clear()
            settings.applySafeFixesOnSave = fixes
            settings.sortImportOnSave = imports
            settings.formatOnSave = format
            WriteCommandAction.runWriteCommandAction(project) { document.setText(input) }
            saveAndAwaitCompletion(myFixture, listOf(document))
            val expectedCalls = buildList {
                if (fixes) add("source.fixAll.biome")
                if (imports) add("source.organizeImports.biome")
                if (format) add("format")
            }
            assertEquals(expectedCalls, calls.toList())
            val keyword = if (fixes) "const" else "let"
            // Biome's fix-all response includes formatting; preserve that server edit.
            val expected = if (fixes || format) "$keyword value = 1;\nconsole.log(value);\n" else "$keyword value=1;console.log(value);\n"
            assertEquals(expected, document.text)
            assertEquals(expected, diskText(document))
        }
    }

    fun testOrganizeImportsPersistsRealServerEdits() {
        val document = openDocuments().last()
        BiomeSettings.getInstance(project).sortImportOnSave = true
        val input = "import { z } from \"./z\";\nimport { a } from \"./a\";\nconsole.log(a,z);\n"
        WriteCommandAction.runWriteCommandAction(project) { document.setText(input) }
        saveAndAwaitCompletion(myFixture, listOf(document))
        val expected = "import { a } from \"./a\";\nimport { z } from \"./z\";\n\nconsole.log(a,z);\n"
        assertEquals(expected, document.text)
        assertEquals(expected, diskText(document))
    }

    fun testSavePreservesLfBytes() = checkLineSeparators("lf", "\n")

    fun testSavePreservesCrLfBytes() = checkLineSeparators("crlf", "\r\n")

    private fun checkLineSeparators(option: String, separator: String) {
        val config = myFixture.findFileInTempDir("biome.json") ?: error("Missing config")
        WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.openapi.vfs.VfsUtil.saveText(config, """{"formatter":{"lineEnding":"$option"}}""")
        }
        val document = openDocuments().last()
        BiomeSettings.getInstance(project).formatOnSave = true
        WriteCommandAction.runWriteCommandAction(project) { document.setText("const value={a:1};\nconsole.log(value);\n") }
        saveAndAwaitCompletion(myFixture, listOf(document))
        val expected = "const value = { a: 1 };\nconsole.log(value);\n"
        assertEquals(expected, document.text)
        assertEquals(expected.replace("\n", separator), diskText(document))
    }

    fun testPartialSuccessPersistsAfterTimeout() {
        val documents = openDocuments()
        val fixed = CopyOnWriteArrayList<String>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>> {
                    fixed += params.textDocument.uri
                    return listOf(Either.forRight(CodeAction("controlled fix").apply {
                        edit = WorkspaceEdit(mapOf(params.textDocument.uri to listOf(
                            TextEdit(Range(Position(0, 0), Position(0, 0)), "// safe fix\n"))))
                    }))
                }
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    if (params.textDocument.uri == fixed.first()) awaitCancellation()
                    return listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "// formatted\n"))
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).apply {
            applySafeFixesOnSave = true
            formatOnSave = true
        }
        dirty(documents)
        saveAndAwaitCompletion(myFixture, documents)
        assertEquals(2, fixed.size)
        assertEquals(1, documents.count { it.text.startsWith("// safe fix\n") })
        assertEquals(1, documents.count { it.text.startsWith("// formatted\n// safe fix\n") })
        documents.forEach { assertEquals(it.text, diskText(it)) }
    }

    fun testMissingServerIsNoOp() {
        // A backing file that was never opened by the LSP has no available server.
        val file = myFixture.findFileInTempDir("first.js") ?: error("Missing file")
        val document = FileDocumentManager.getInstance().getDocument(file) ?: error("Missing document")
        val original = document.text
        runService { BiomeServerService.getInstance(project).executeFeatures(document, EnumSet.allOf(BiomeServerService.Feature::class.java)) }
        assertEquals(original, document.text)
    }

    private fun checkBatchIsolation(firstRequest: suspend () -> Unit) {
        val documents = openDocuments()
        val requests = CopyOnWriteArrayList<String>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    requests += params.textDocument.uri
                    if (requests.size == 1) firstRequest()
                    return listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "// saved by Biome\n"))
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        dirty(documents)
        saveAndAwaitCompletion(myFixture, documents)
        assertEquals("One local failure must leave the other document eligible", 2, requests.size)
        val saved = documents.filter { it.text.startsWith("// saved by Biome\n") }
        assertEquals(1, saved.size)
        assertEquals(saved.single().text, diskText(saved.single()))
    }

    private fun openDocuments(): List<Document> = listOf("first.js", "second.js").map { path ->
        val file = myFixture.findFileInTempDir(path) ?: error("Missing fixture $path")
        myFixture.configureFromExistingVirtualFile(file)
        waitUntilFileOpenedByLspServer(project, file, "com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor")
        FileDocumentManager.getInstance().getDocument(file) ?: error("Missing document $path")
    }

    private fun dirty(documents: List<Document>) {
        WriteCommandAction.runWriteCommandAction(project) {
            documents.forEach { it.insertString(it.textLength, "// changed\n") }
        }
    }

    private fun diskText(document: Document): String {
        val file = FileDocumentManager.getInstance().getFile(document) ?: error("Document has no backing file")
        awaitPhysicalFileWrites(file)
        return Files.readString(Path.of(file.path))
    }

}
