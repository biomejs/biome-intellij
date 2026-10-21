package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.platform.lsp.api.LspBundle
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeOnTypeLspTest : BiomeLspFixtureTestCase() {
    private var nativeCommandConnection: MessageBusConnection? = null
    private val settings get() = BiomeSettings.getInstance(project)
    private val document get() = myFixture.editor.document

    override fun setUp() {
        super.setUp()
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        val root = myFixture.tempDirFixture.findOrCreateDir(".")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root)
        myFixture.tempDirFixture.createFile("biome.json", """{"formatter":{"indentStyle":"space","indentWidth":2}}""")
        val fixtureProject = project
        val connection = ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
        nativeCommandConnection = connection
        connection.subscribe(CommandListener.TOPIC,
            object : CommandListener {
                override fun commandFinished(event: CommandEvent) {
                    if (event.project !== fixtureProject ||
                        event.commandName != LspBundle.message("command.name.lsp.on.type.formatting")) return
                    assertTrue("Native formatting must finish inside its write action",
                        ApplicationManager.getApplication().isWriteAccessAllowed)
                    val psiDocuments = PsiDocumentManager.getInstance(fixtureProject)
                    // Native edits arrive after the fixture's typing callback; keep PSI current before diagnostics resume.
                    psiDocuments.commitAllDocuments()
                    assertTrue(psiDocuments.isCommitted(document))
                }
            })
    }

    override fun tearDown() {
        try {
            nativeCommandConnection?.disconnect()
        } finally {
            nativeCommandConnection = null
            super.tearDown()
        }
    }

    fun testDefaultOffThenEnabledThenDisabledWhileServerStaysRunning() {
        open("index.js", "const first=[1,2<caret>];\nconst second=[1,2];\nconst third=[1,2];\n")
        type(']')
        assertEquals("const first=[1,2];\nconst second=[1,2];\nconst third=[1,2];\n", document.text)
        settings.formatOnType = true
        myFixture.editor.caretModel.moveToOffset(document.text.indexOf(']', document.text.indexOf("second")))
        type(']')
        assertEquals("const first=[1,2];\nconst second = [1, 2];\nconst third=[1,2];\n", document.text)
        settings.formatOnType = false
        myFixture.editor.caretModel.moveToOffset(document.text.lastIndexOf(']'))
        type(']')
        assertEquals("const first=[1,2];\nconst second = [1, 2];\nconst third=[1,2];\n", document.text)
    }

    fun testReleasedJavaScriptClosingBraceFormatsRegionAndSupportsUndo() {
        settings.formatOnType = true
        open("index.js", "function answer( ){return {value:42}<caret>\nconst sibling=  2;\n")
        type('}')
        assertEquals("function answer() {\n  return { value: 42 };\n}\nconst sibling=  2;\n", document.text)
        assertEquals(document.text.indexOf("}\nconst sibling") + 1, myFixture.editor.caretModel.offset)
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
        assertEquals("function answer( ){return {value:42}}\nconst sibling=  2;\n", document.text)
        type('x')
        assertTrue(document.text.contains("}}x"))
    }

    fun testReleasedTypeScriptClosingBracketFormatsStatementAndPreservesSibling() {
        settings.formatOnType = true
        open("index.ts", "const values : number[]=[1,2<caret>;\nconst sibling=  2;\n")
        type(']')
        assertEquals("const values: number[] = [1, 2];\nconst sibling=  2;\n", document.text)
    }

    fun testReleasedClosingParenFormatsArguments() {
        settings.formatOnType = true
        open("index.js", "const value=call(1,2<caret>;\nconst sibling=  2;\n")
        type(')')
        assertEquals("const value = call(1, 2);\nconst sibling=  2;\n", document.text)
    }

    fun testTypingOverExistingClosingParenFormatsWithoutInsertingAnother() {
        settings.formatOnType = true
        open("index.js", "const value=call(1,2<caret>);\nconst sibling=  2;\n")
        type(')')
        assertEquals("const value = call(1, 2);\nconst sibling=  2;\n", document.text)
        assertEquals(document.text.indexOf(';'), myFixture.editor.caretModel.offset)
    }

    fun testSelectionReplacementStaysUnformattedAndNextTriggerFormats() {
        settings.formatOnType = true
        open("selection.js", "const values=[1,2<caret>X;\nconst next=call(1,2);\n")
        val editor = myFixture.editor
        val offset = editor.caretModel.offset
        editor.selectionModel.setSelection(offset, offset + 1)
        assertEquals("X", editor.selectionModel.selectedText)
        type(']')
        assertEquals("const values=[1,2];\nconst next=call(1,2);\n", document.text)
        assertEquals(offset + 1, editor.caretModel.offset)
        editor.caretModel.moveToOffset(document.text.lastIndexOf(')'))
        type(')')
        assertEquals("const values=[1,2];\nconst next = call(1, 2);\n", document.text)
    }

    fun testReadOnlyEditorIsUnchangedByTyping() {
        settings.formatOnType = true
        open("readonly.js", "const value=call(1,2<caret>);\n")
        val before = document.text
        document.setReadOnly(true)
        try {
            type(')')
            assertEquals(before, document.text)
        } finally {
            document.setReadOnly(false)
        }
    }

    fun testReleasedJsonClosingBracketFormatsArrayOnly() {
        settings.formatOnType = true
        open("index.json", "{\"values\":[1,2<caret>,\"sibling\" :  2}")
        type(']')
        assertEquals("{\"values\":[1, 2],\"sibling\" :  2}", document.text)
    }

    fun testNonTriggerDoesNotFormat() {
        settings.formatOnType = true
        open("index.js", "const value=1<caret>")
        type(';')
        assertEquals("const value=1;", document.text)
    }

    fun testIgnoredFileReturnsNoEdits() {
        settings.formatOnType = true
        myFixture.tempDirFixture.createFile("ignored/biome.json", """{"root":true,"files":{"includes":["**","!**/index.js"]}}""")
        open("ignored/index.js", "const value=[1,2<caret>;")
        type(']')
        assertEquals("const value=[1,2];", document.text)
    }

    fun testFormatterDisabledReturnsNoEdits() {
        settings.formatOnType = true
        myFixture.tempDirFixture.createFile("disabled/biome.json", """{"root":true,"formatter":{"enabled":false}}""")
        open("disabled/index.js", "const value=[1,2<caret>;")
        type(']')
        assertEquals("const value=[1,2];", document.text)
    }

    fun testNestedWorkspaceUsesItsOwnFormatterConfiguration() {
        settings.formatOnType = true
        myFixture.tempDirFixture.createFile("nested/biome.json", """{"root":true,"formatter":{"indentStyle":"space","indentWidth":4}}""")
        open("parent.js", "const value=1;<caret>")
        open("nested/index.js", "function answer(){return {value:42}<caret>")
        type('}')
        assertEquals("function answer() {\n    return { value: 42 };\n}", document.text)
    }

    private fun open(path: String, marked: String) {
        val offset = marked.indexOf("<caret>")
        val file = myFixture.addFileToProject(path, marked.replace("<caret>", "")).virtualFile
        assertTrue(file.isInLocalFileSystem)
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.editor.caretModel.moveToOffset(offset)
        waitUntilFileOpenedByLspServer(project, file, BiomeLspServerDescriptor::class.java.name)
        val server = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
            .single { it.state == LspServerState.Running && it.descriptor.isSupportedFile(file) }
        assertEquals("2.5.15", server.initializeResult?.serverInfo?.version)
        // File-open notification can precede Biome's dynamic registration. Observe SDK readiness only in tests.
        val triggers = server.javaClass.methods.single { it.name.startsWith("getOnTypeFormattingTriggerCharacters") }
        PlatformTestUtil.waitWithEventsDispatching("Biome did not register its on-type triggers", {
            (triggers.invoke(server, file) as? List<*>)?.contains("}") == true
        }, 15)
    }

    private fun type(character: Char) {
        val scope = nativeScope()
        val before = scope.coroutineContext[Job]!!.children.toSet()
        val editor = myFixture.editor
        val context = EditorUtil.getEditorDataContext(editor)
        val actions = ActionManagerEx.getInstanceEx()
        val added = WriteAction.compute<List<Job>, RuntimeException> {
            // Use the fixture's public typing path without its shortcut dispatcher, which forbids a write action.
            actions.fireBeforeEditorTyping(character, context)
            TypedAction.getInstance().actionPerformed(editor, character, context)
            actions.fireAfterEditorTyping(character, context)
            // Capture native jobs before committing PSI or dispatching events.
            val jobs = scope.coroutineContext[Job]!!.children.filter { it !in before }.toList()
            // Keep the typed document and PSI consistent before background diagnostics can read either.
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            jobs
        }
        assertFalse("Native jobs must be awaited outside the write action",
            ApplicationManager.getApplication().isWriteAccessAllowed)
        val failure = AtomicReference<Throwable?>()
        val completed = AtomicInteger()
        added.forEach { job ->
            job.invokeOnCompletion {
                if (it != null && it !is CancellationException) failure.set(it)
                completed.incrementAndGet()
            }
        }
        PlatformTestUtil.waitWithEventsDispatching("Native on-type formatting did not finish", {
            completed.get() == added.size
        }, 15)
        failure.get()?.let { throw AssertionError("SDK editor work failed during typing", it) }
    }

    /** Test-only completion observation; the native service and jobs are never replaced or cancelled. */
    private fun nativeScope(): CoroutineScope {
        val type = Class.forName("com.intellij.platform.lsp.impl.LspCoroutineScopeService", true,
            LspServerDescriptor::class.java.classLoader)
        return type.getMethod("getCs").invoke(project.getService(type)) as CoroutineScope
    }
}
