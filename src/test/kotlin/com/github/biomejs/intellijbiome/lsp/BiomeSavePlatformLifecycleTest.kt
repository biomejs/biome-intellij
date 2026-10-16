package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.services.BiomeLspRequests
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.services.DefaultBiomeLspRequests
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.openapi.command.WriteCommandAction
import com.github.biomejs.intellijbiome.actions.BiomeCheckOnSaveAction
import com.github.biomejs.intellijbiome.actions.BiomeSaveOutcome
import com.github.biomejs.intellijbiome.actions.runBiomeSaveOperation
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
class BiomeSavePlatformLifecycleTest : BiomeLspFixtureTestCase() {
    private var verifyAfterDisposal: (() -> Unit)? = null

    override fun tearDown() {
        val verify = verifyAfterDisposal
        verifyAfterDisposal = null
        var failure: Throwable? = null
        try {
            super.tearDown()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            verify?.invoke()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

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

    fun testUsesPlatformDocumentUpdatingHook() {
        assertTrue(com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave::class.java
            .isAssignableFrom(BiomeCheckOnSaveAction::class.java))
    }

    fun testCurrentFormatterConvertsCrLfToLfWithoutTextEdits() =
        checkCurrentFormatterLfOutput("const value = 1;\nconsole.log(value);\n", expectTextEdits = false)

    fun testCurrentFormatterConvertsCrLfToLfWithSpaceOnlyEdits() =
        checkCurrentFormatterLfOutput("const value=1;\nconsole.log(value);\n", expectTextEdits = true)

    fun testLargeFormattingResponseDoesNotRequireWholeDocumentDiff() {
        val config = myFixture.findFileInTempDir("biome.json") ?: error("Missing config")
        WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.openapi.vfs.VfsUtil.saveText(config, """{"formatter":{"lineEnding":"lf"},"linter":{"enabled":false}}""")
        }
        val events = RuntimeGateEvents(project, testRootDisposable)
        val document = openDocuments().last()
        val file = FileDocumentManager.getInstance().getFile(document)!!
        events.awaitDiagnostics(file, "2.5.15")
        val input = (0 until 12_000).joinToString("") { "const value$it = {\"name\":\"value$it\",\"active\":true};\n" }
        val expected = (0 until 12_000).joinToString("") { "const value$it = { name: \"value$it\", active: true };\n" }
        assertTrue("The regression must stay below Biome's default file limit", input.length < 1_048_576)
        // Biome 2.5.15 produces this output. Keep server diff latency outside this
        // regression: the plugin must apply a valid response below the file limit.
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> =
                    listOf(TextEdit(Range(Position(0, 0), Position(12_000, 0)), expected))
            }), testRootDisposable)
        WriteCommandAction.runWriteCommandAction(project) { document.setText(input) }
        BiomeSettings.getInstance(project).formatOnSave = true
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertEquals(expected, document.text)
        assertEquals(expected, diskText(document))
    }

    private fun checkCurrentFormatterLfOutput(input: String, expectTextEdits: Boolean) {
        val config = myFixture.findFileInTempDir("biome.json") ?: error("Missing config")
        WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.openapi.vfs.VfsUtil.saveText(config, """{"formatter":{"lineEnding":"lf"}}""")
        }
        val events = RuntimeGateEvents(project, testRootDisposable)
        val document = openDocuments().last()
        val manager = FileDocumentManager.getInstance()
        val file = manager.getFile(document)!!
        events.awaitDiagnostics(file, "2.5.15")
        WriteCommandAction.runWriteCommandAction(project) {
            file.detectedLineSeparator = "\r\n"
            document.setText("const saved = 0;\n")
        }
        manager.saveDocument(document)
        assertEquals("const saved = 0;\r\n", diskText(document))
        val response = AtomicReference<List<TextEdit>>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit>? =
                    DefaultBiomeLspRequests.formatting(server, params).also { response.set(it) }
            }), testRootDisposable)
        WriteCommandAction.runWriteCommandAction(project) { document.setText(input) }
        val changedRanges = mutableListOf<Pair<Int, Int>>()
        document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                changedRanges += event.offset to event.oldLength
            }
        }, testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        saveAndAwaitCompletion(myFixture, listOf(document))
        val edits = response.get() ?: error("Real formatter did not respond")
        assertEquals(expectTextEdits, edits.isNotEmpty())
        assertTrue("This response must exercise edits with no separator text",
            edits.all { !it.newText.contains('\n') && !it.newText.contains('\r') })
        val formatted = "const value = 1;\nconsole.log(value);\n"
        assertEquals(formatted, document.text)
        assertEquals("The complete LF output must determine disk separators", formatted, diskText(document))
        assertEquals("\n", file.detectedLineSeparator)
        assertEquals(expectTextEdits, changedRanges.isNotEmpty())
        assertTrue("Formatting must leave the unchanged second line outside its edits",
            changedRanges.all { (offset, length) -> offset + length < input.indexOf('\n') })
        BiomeSettings.getInstance(project).formatOnSave = false
        val editor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
        undo.undo(editor)
        assertEquals(input, document.text)
        manager.saveDocument(document)
        assertEquals(input.replace("\n", "\r\n"), diskText(document))
        undo.redo(editor)
        assertEquals(formatted, document.text)
        manager.saveDocument(document)
        assertEquals(formatted, diskText(document))
    }

    fun testNullFormattingResponsePreservesCrLfBytes() {
        val document = openDocuments().last()
        val manager = FileDocumentManager.getInstance()
        val file = manager.getFile(document)!!
        WriteCommandAction.runWriteCommandAction(project) {
            file.detectedLineSeparator = "\r\n"
            document.setText("const saved = 0;\n")
        }
        manager.saveDocument(document)
        assertEquals("const saved = 0;\r\n", diskText(document))
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit>? = null
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        WriteCommandAction.runWriteCommandAction(project) { document.setText("const value=1;\n") }
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertEquals("const value=1;\n", document.text)
        assertEquals("const value=1;\r\n", diskText(document))
        assertEquals("\r\n", file.detectedLineSeparator)
    }

    fun testSplitCrLfEditsDoNotInsertBlankLines() {
        val document = openDocuments().last()
        val input = "const value = 1;\nconsole.log(value);\n"
        val lines = input.lines()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> =
                    listOf(0, 1).map { line ->
                        val end = Position(line, lines[line].length)
                        TextEdit(Range(end, end), "\r")
                    }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        WriteCommandAction.runWriteCommandAction(project) { document.setText(input) }
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertEquals("CRLF split across edits and existing text must normalize once", input, document.text)
        assertEquals(input.replace("\n", "\r\n"), diskText(document))
        BiomeSettings.getInstance(project).formatOnSave = false
        val editor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
        undo.undo(editor)
        assertEquals(input, document.text)
        assertEquals(input, diskText(document))
        undo.redo(editor)
        assertEquals(input, document.text)
        assertEquals(input.replace("\n", "\r\n"), diskText(document))
    }

    fun testAdjacentCrLfEditsDoNotInsertBlankLines() {
        val document = openDocuments().last()
        val input = "const value = 1;\nconsole.log(value);\n"
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> =
                    listOf(
                        TextEdit(Range(Position(0, 17), Position(1, 0)), "\n"),
                        TextEdit(Range(Position(0, 16), Position(0, 17)), "\r"),
                    )
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        WriteCommandAction.runWriteCommandAction(project) { document.setText("const value = 1; \nconsole.log(value);\n") }
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertEquals("CRLF split between adjacent replacements must normalize once", input, document.text)
        assertEquals(input.replace("\n", "\r\n"), diskText(document))
    }

    fun testCrLfOnlyEditPersists() = checkSeparatorOnlyEdit("\n", "\r\n", moveCaretBeforeRedo = true)

    fun testLfOnlyEditPersists() = checkSeparatorOnlyEdit("\r\n", "\n")

    fun testSeparatorPersistsWhenEarlierEditsReturnToSavedText() = checkSeparatorOnlyEdit("\n", "\r\n", true)

    fun testTextAndCrLfFormattingUndoRedoRestoresBytes() = checkTextAndSeparatorUndoRedo("\n", "\r\n")

    fun testTextAndLfFormattingUndoRedoRestoresBytes() = checkTextAndSeparatorUndoRedo("\r\n", "\n")

    private fun checkTextAndSeparatorUndoRedo(originalSeparator: String, requestedSeparator: String) {
        val document = openDocuments().last()
        val file = FileDocumentManager.getInstance().getFile(document)!!
        val input = "const value=2;\n"
        val formatted = "const value = 2;\n"
        val boundaryBytes = AtomicReference<String>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    boundaryBytes.set(diskText(document))
                    return listOf(TextEdit(Range(Position(0, 0), Position(1, 0)), formatted.replace("\n", requestedSeparator)))
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        WriteCommandAction.runWriteCommandAction(project) {
            file.detectedLineSeparator = originalSeparator
            document.setText(input)
        }
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertEquals(input.replace("\n", originalSeparator), boundaryBytes.get())
        assertEquals(formatted.replace("\n", requestedSeparator), diskText(document))
        assertEquals(formatted, document.text)
        BiomeSettings.getInstance(project).formatOnSave = false
        val editor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
        undo.undo(editor)
        assertEquals(input, document.text)
        FileDocumentManager.getInstance().saveDocument(document)
        assertEquals("Undo and save must restore both text and separators", input.replace("\n", originalSeparator), diskText(document))
        assertTrue(undo.isRedoAvailable(editor))
        undo.redo(editor)
        assertEquals(formatted, document.text)
        FileDocumentManager.getInstance().saveDocument(document)
        assertEquals("Redo and save must restore both text and separators", formatted.replace("\n", requestedSeparator), diskText(document))
    }

    fun testSeparatorOnlyFormattingPreservesExternalDiskWrite() {
        val document = openDocuments().last()
        val file = FileDocumentManager.getInstance().getFile(document)!!
        val input = "const value = 2;\n"
        val external = "const external = 99;\n"
        val boundaryBytes = AtomicReference<String>()
        val cachedBytes = AtomicReference<String>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    boundaryBytes.set(diskText(document))
                    assertEquals(input, String(file.contentsToByteArray(), Charsets.UTF_8))
                    writeOutsideVfs(file, external)
                    assertEquals(external, diskText(document))
                    cachedBytes.set(String(file.contentsToByteArray(), Charsets.UTF_8))
                    return listOf(TextEdit(Range(Position(0, 0), Position(1, 0)), input.replace("\n", "\r\n")))
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        WriteCommandAction.runWriteCommandAction(project) {
            file.detectedLineSeparator = "\n"
            document.setText(input)
        }
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertEquals(input, boundaryBytes.get())
        assertEquals("Raw disk writes must remain invisible to cached VFS content in this regression", input, cachedBytes.get())
        assertEquals("A stale formatting response must preserve the external disk edit", external, diskText(document))
    }

    fun testDocumentSaveDetectsExternalDiskWrite() {
        val document = openDocuments().last()
        val manager = FileDocumentManager.getInstance()
        val file = manager.getFile(document)!!
        val input = "const value = 2;\n"
        val external = "const external = 99;\n"
        WriteCommandAction.runWriteCommandAction(project) {
            file.detectedLineSeparator = "\n"
            document.setText(input)
        }
        manager.saveDocument(document)
        assertEquals(input, diskText(document))
        assertEquals(input, String(file.contentsToByteArray(), Charsets.UTF_8))
        val asked = java.util.concurrent.atomic.AtomicBoolean()
        (manager as com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl).setAskReloadFromDisk(testRootDisposable,
            object : com.intellij.openapi.fileEditor.impl.MemoryDiskConflictResolver() {
                override fun askReloadFromDisk(file: com.intellij.openapi.vfs.VirtualFile, document: Document): Boolean {
                    asked.set(true)
                    return true
                }
            })
        WriteCommandAction.runWriteCommandAction(project) { document.setText("const value = 3;\n") }
        writeOutsideVfs(file, external)
        assertEquals(input, String(file.contentsToByteArray(), Charsets.UTF_8))
        manager.saveDocument(document)
        PlatformTestUtil.waitWithEventsDispatching("Normal save must detect the external-write conflict", { asked.get() }, 10)
        assertEquals(external, diskText(document))
        assertEquals(external, document.text)
    }

    fun testSeparatorUndoPreservesExternalDiskWrite() = checkSeparatorHistoryPreservesExternalWrite(false)

    fun testSeparatorRedoPreservesExternalDiskWrite() = checkSeparatorHistoryPreservesExternalWrite(true)

    private fun checkSeparatorHistoryPreservesExternalWrite(redo: Boolean) {
        val document = openDocuments().last()
        val file = FileDocumentManager.getInstance().getFile(document)!!
        val input = "const value = 2;\n"
        val external = "const external = 99;\n"
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> =
                    listOf(TextEdit(Range(Position(0, 0), Position(1, 0)), input.replace("\n", "\r\n")))
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        WriteCommandAction.runWriteCommandAction(project) {
            file.detectedLineSeparator = "\n"
            document.setText(input)
        }
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertEquals(input.replace("\n", "\r\n"), diskText(document))
        val editor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
        if (redo) undo.undo(editor)
        val beforeExternalWrite = diskText(document)
        assertEquals(beforeExternalWrite, String(file.contentsToByteArray(), Charsets.UTF_8))
        writeOutsideVfs(file, external)
        assertEquals(beforeExternalWrite, String(file.contentsToByteArray(), Charsets.UTF_8))
        val reported = AtomicReference<com.intellij.openapi.command.undo.UnexpectedUndoException>()
        val reports = com.intellij.openapi.command.impl.UndoReportHandler.EP_NAME
        val handler = object : com.intellij.openapi.command.impl.UndoReportHandler by reports.extensionList.first() {
            override fun reportException(project: com.intellij.openapi.project.Project?, exception: com.intellij.openapi.command.undo.UnexpectedUndoException, isUndo: Boolean): Boolean {
                reported.set(exception)
                return true
            }
        }
        com.intellij.testFramework.ExtensionTestUtil.maskExtensions(reports, listOf(handler), testRootDisposable)
        if (redo) undo.redo(editor) else undo.undo(editor)
        assertEquals("Undo/redo must not overwrite external disk changes", external, diskText(document))
        assertNotNull("An unsafe separator conversion must report the undo failure", reported.get())
        assertTrue(reported.get().message.orEmpty().contains(file.name))
    }

    private fun writeOutsideVfs(file: com.intellij.openapi.vfs.VirtualFile, text: String) {
        awaitPhysicalFileWrites(file)
        val path = Path.of(file.path)
        val timestamp = file.timeStamp
        Files.writeString(path, text)
        Files.setLastModifiedTime(path, FileTime.fromMillis(timestamp + 10_000))
    }

    private fun checkSeparatorOnlyEdit(
        originalSeparator: String,
        requestedSeparator: String,
        earlierEdit: Boolean = false,
        moveCaretBeforeRedo: Boolean = false,
    ) {
        // This control checks document undo groups. Native caret restoration can
        // consume a redo without applying that group; make caret motion transparent.
        com.intellij.openapi.util.registry.Registry.get("ide.undo.transparent.caret.movement")
            .setValue(true, testRootDisposable)
        val document = openDocuments().last()
        val file = FileDocumentManager.getInstance().getFile(document)!!
        val formatted = "const value = 1;\nconsole.log(value);\n"
        var requests = 0
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>> =
                    listOf(Either.forRight(CodeAction("temporary fix").apply {
                        edit = WorkspaceEdit(mapOf(params.textDocument.uri to listOf(
                            TextEdit(Range(Position(0, 0), Position(0, 0)), "// fixed\n"))))
                    }))

                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    requests++
                    val endLine = if (earlierEdit) 3 else 2
                    return listOf(TextEdit(Range(Position(0, 0), Position(endLine, 0)), formatted.replace("\n", requestedSeparator)))
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).apply {
            applySafeFixesOnSave = earlierEdit
            formatOnSave = true
        }
        WriteCommandAction.runWriteCommandAction(project) {
            file.detectedLineSeparator = originalSeparator
            document.setText(formatted)
        }
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertEquals("Separator conversion must not trigger a recursive save action", 1, requests)
        assertEquals(formatted, document.text)
        assertEquals(formatted.replace("\n", requestedSeparator), diskText(document))
        assertEquals(requestedSeparator, file.detectedLineSeparator)
        val editor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
        assertTrue("Separator-only formatting must be undoable", undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals("Undo must preserve normalized editor text", formatted, document.text)
        assertEquals(formatted.replace("\n", originalSeparator), diskText(document))
        assertTrue(undo.isRedoAvailable(editor))
        if (moveCaretBeforeRedo) myFixture.editor.caretModel.moveToOffset(formatted.length - 1)
        undo.redo(editor)
        assertEquals(formatted, document.text)
        assertEquals(formatted.replace("\n", requestedSeparator), diskText(document))
        assertEquals("Undo/redo must not restart Biome", 1, requests)
    }

    fun testIdeFormatOnSaveOrderingAndUndo() {
        val document = openDocuments().first()
        val file = FileDocumentManager.getInstance().getFile(document)!!
        myFixture.configureFromExistingVirtualFile(file)
        val formatOptions = com.intellij.codeInsight.actions.onSave.FormatOnSaveOptions.getInstance(project)
        val originalEnabled = formatOptions.isRunOnSaveEnabled
        val input = "let value={a:1};"
        val beforeBiome = AtomicReference<String>()
        val requests = CopyOnWriteArrayList<String>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>> {
                    if (beforeBiome.get() == null) {
                        beforeBiome.set(com.intellij.openapi.application.readAction { document.text })
                    }
                    val stage = if (params.context.only.single() == "source.fixAll.biome") "fix" else "imports"
                    requests += stage
                    return listOf(Either.forRight(CodeAction(stage).apply {
                        edit = WorkspaceEdit(mapOf(params.textDocument.uri to listOf(
                            TextEdit(Range(Position(0, 0), Position(0, 0)), "// $stage\n"))))
                    }))
                }
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    requests += "format"
                    return listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "// format\n"))
                }
            }), testRootDisposable)
        try {
            formatOptions.isRunOnSaveEnabled = true
            BiomeSettings.getInstance(project).apply {
                applySafeFixesOnSave = true
                sortImportOnSave = true
                formatOnSave = true
                enableLspFormat = false
            }
            WriteCommandAction.runWriteCommandAction(project) { document.setText(input) }
            saveAndAwaitCompletion(myFixture, listOf(document))
            assertNotNull(beforeBiome.get())
            val modernFormatter = com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
                .DocumentUpdatingActionOnSave::class.java.isAssignableFrom(
                    Class.forName("com.intellij.codeInsight.actions.onSave.FormatOnSaveAction"))
            if (modernFormatter) {
                assertEquals("The document-updating IDE formatter follows the declared Biome-first order", input, beforeBiome.get())
            } else {
                assertFalse("253 must finish its legacy IDE formatter before Biome starts", input == beforeBiome.get())
            }
            assertEquals(listOf("fix", "imports", "format"), requests.toList())
            val biomeOutput = "// format\n// imports\n// fix\n" + beforeBiome.get()
            val saved = if (modernFormatter) {
                WriteCommandAction.runWriteCommandAction(project, com.intellij.openapi.util.Computable {
                    val copy = com.intellij.psi.PsiFileFactory.getInstance(project)
                        .createFileFromText("native-control.js", myFixture.file.fileType, biomeOutput)
                    com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(copy).text
                }).also { assertFalse("The modern IDE formatter must change the Biome output control", it == biomeOutput) }
            } else biomeOutput
            assertEquals(saved, document.text)
            assertEquals(saved, diskText(document))
            val editor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
            val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
            assertTrue(undo.isUndoAvailable(editor))
            undo.undo(editor)
            if (modernFormatter) {
                assertEquals("The first undo reverses only the later native formatter", biomeOutput, document.text)
                assertTrue(undo.isUndoAvailable(editor))
                undo.undo(editor)
            }
            assertEquals("All Biome feature edits must be one undo group", beforeBiome.get(), document.text)
            assertTrue(undo.isRedoAvailable(editor))
            undo.redo(editor)
            if (modernFormatter) {
                assertEquals("The first redo restores the complete Biome feature group", biomeOutput, document.text)
                assertTrue(undo.isRedoAvailable(editor))
                undo.redo(editor)
            }
            assertEquals(saved, document.text)
            assertEquals("Undo/redo must not restart Biome", 3, requests.size)
        } finally {
            formatOptions.isRunOnSaveEnabled = originalEnabled
        }
    }

    fun testTypingDuringSaveCancelsOnlyThatDocument() {
        val documents = openDocuments()
        val requested = AtomicReference<String>()
        val release = CompletableDeferred<Unit>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    if (requested.compareAndSet(null, params.textDocument.uri)) {
                        // Deliberately deliver a response even after cancellation, as a raced
                        // transport completion might do. The real service must reject it.
                        withContext(NonCancellable) { withTimeout(10_000) { release.await() } }
                    }
                    return listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "// formatted\n"))
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        dirty(documents)
        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            PlatformTestUtil.waitWithEventsDispatching("Save request did not start", { requested.get() != null }, 10)
            val typed = documents.single {
                requested.get().endsWith("/" + FileDocumentManager.getInstance().getFile(it)!!.name)
            }
            WriteCommandAction.runWriteCommandAction(project) { typed.setText("// newer typing\n") }
            release.complete(Unit)
            val manager = com.intellij.ide.actionsOnSave.impl.ActionsOnSaveManager.getInstance(project)
            PlatformTestUtil.waitWithEventsDispatching("Save actions did not finish", { !manager.hasPendingActions() }, 15)
            assertEquals("// newer typing\n", typed.text)
            assertTrue("Typing after save must remain unsaved", FileDocumentManager.getInstance().isDocumentUnsaved(typed))
            val other = documents.single { it !== typed }
            assertTrue(other.text.startsWith("// formatted\n"))
            assertEquals(other.text, diskText(other))
        } finally {
            release.complete(Unit)
        }
    }

    fun testProjectDisposalCancelsPendingSave() {
        val document = openDocuments().last()
        val started = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val cancellation = AtomicReference<CancellationException>()
        val notifications = CopyOnWriteArrayList<com.intellij.notification.Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(com.intellij.notification.Notifications.TOPIC,
            object : com.intellij.notification.Notifications {
                override fun notify(notification: com.intellij.notification.Notification) {
                    if (notification.groupId == "Biome") notifications += notification
                }
            })
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (failure: CancellationException) {
                        cancellation.set(failure)
                        throw failure
                    } finally {
                        canceled.complete(Unit)
                    }
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        dirty(listOf(document))
        val original = document.text
        FileDocumentManager.getInstance().saveAllDocuments()
        PlatformTestUtil.waitWithEventsDispatching("Pending save did not start", { started.isCompleted }, 10)
        val disposedProject = project
        // Let the fixture close its editors before disposing the real project. Its
        // teardown cannot access PSI services after an explicit forceCloseProject.
        verifyAfterDisposal = {
            assertTrue(disposedProject.isDisposed)
            assertTrue("Project disposal must cancel the pending save request", canceled.isCompleted)
            assertNotNull(cancellation.get())
            assertFalse("Project disposal must cancel before the local deadline", cancellation.get() is TimeoutCancellationException)
            assertEquals(original, document.text)
            assertTrue(notifications.isEmpty())
        }
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
