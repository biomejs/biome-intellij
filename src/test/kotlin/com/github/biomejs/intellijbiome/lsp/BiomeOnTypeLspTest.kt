package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.services.BiomeOnTypeFormattingService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.*
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import java.util.concurrent.ConcurrentLinkedQueue

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeOnTypeLspTest : BiomeLspFixtureTestCase() {
    private lateinit var scope: CoroutineScope
    private val requests = ConcurrentLinkedQueue<DocumentOnTypeFormattingParams>()

    override fun setUp() {
        super.setUp()
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        val root = myFixture.tempDirFixture.findOrCreateDir(".")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root)
        myFixture.tempDirFixture.createFile("biome.json", """{"formatter":{"indentStyle":"space","indentWidth":2}}""")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        project.replaceService(BiomeOnTypeFormattingService::class.java,
            BiomeOnTypeFormattingService(project, scope, {
                LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
            }) { server, params ->
                requests.add(params)
                server.sendRequest { it.textDocumentService.onTypeFormatting(params) }
            }, testRootDisposable)
        BiomeSettings.getInstance(project).formatOnType = true
    }

    override fun tearDown() {
        try {
            scope.cancel()
            scope.coroutineContext[Job]!!.children.toList().forEach(::await)
        } finally { super.tearDown() }
    }

    fun testReleasedJavaScriptClosingBraceFormatsRegionAndSupportsUndo() {
        open("index.js", "function answer( ){return {value:42}<caret>\nconst sibling=  2;\n")
        type('}')
        assertEquals("function answer() {\n  return { value: 42 };\n}\nconst sibling=  2;\n", myFixture.editor.document.text)
        assertEquals("}", requests.single().ch)
        assertEquals(myFixture.editor.document.text.indexOf("}\nconst sibling") + 1, myFixture.editor.caretModel.offset)
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
        assertEquals("function answer( ){return {value:42}}\nconst sibling=  2;\n", myFixture.editor.document.text)
        myFixture.type('x')
        assertTrue(myFixture.editor.document.text.contains("}}x"))
    }

    fun testReleasedTypeScriptClosingBracketFormatsStatementAndPreservesSibling() {
        open("index.ts", "const values : number[]=[1,2<caret>;\nconst sibling=  2;\n")
        type(']')
        assertEquals("const values: number[] = [1, 2];\nconst sibling=  2;\n", myFixture.editor.document.text)
        assertEquals("]", requests.single().ch)
    }

    fun testReleasedClosingParenFormatsArguments() {
        open("index.js", "const value=call(1,2<caret>;\nconst sibling=  2;\n")
        type(')')
        assertEquals("const value = call(1, 2);\nconst sibling=  2;\n", myFixture.editor.document.text)
        assertEquals(")", requests.single().ch)
    }

    fun testTypingOverExistingClosingParenFormatsOnceWithoutInsertingAnother() {
        open("index.js", "const value=call(1,2<caret>);\nconst sibling=  2;\n")
        val before = myFixture.editor.document.text
        val stamp = myFixture.editor.document.modificationStamp
        val caret = myFixture.editor.caretModel.offset
        myFixture.type(')')
        assertEquals("IDE overtype should only advance the caret", before, myFixture.editor.document.text)
        assertEquals(stamp, myFixture.editor.document.modificationStamp)
        assertEquals(caret + 1, myFixture.editor.caretModel.offset)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        scope.coroutineContext[Job]!!.children.toList().forEach(::await)
        assertEquals(1, requests.size)
        assertEquals("const value = call(1, 2);\nconst sibling=  2;\n", myFixture.editor.document.text)
    }

    fun testTypingConsumedByAnotherDelegateDoesNotRequest() {
        open("index.js", "const value=call(1,2<caret>);\n")
        com.intellij.codeInsight.editorActions.TypedHandlerDelegate.EP_NAME.point.registerExtension(
            object : com.intellij.codeInsight.editorActions.TypedHandlerDelegate() {
                override fun beforeCharTyped(c: Char, project: com.intellij.openapi.project.Project,
                    editor: com.intellij.openapi.editor.Editor, file: com.intellij.psi.PsiFile,
                    fileType: com.intellij.openapi.fileTypes.FileType) = Result.STOP
            }, com.intellij.openapi.extensions.LoadingOrder.FIRST, testRootDisposable)
        val before = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        type(')')
        assertTrue(requests.isEmpty())
        assertEquals(before, myFixture.editor.document.text)
        assertEquals(caret, myFixture.editor.caretModel.offset)
    }

    fun testReleasedJsonClosingBracketFormatsArrayOnly() {
        open("index.json", "{\"values\":[1,2<caret>,\"sibling\" :  2}")
        type(']')
        assertEquals("{\"values\":[1, 2],\"sibling\" :  2}", myFixture.editor.document.text)
        assertEquals("]", requests.single().ch)
    }

    fun testNonTriggerAndDisabledSettingNeverSendRequest() {
        open("index.js", "const value=1<caret>")
        type(';')
        assertTrue(requests.isEmpty())
        BiomeSettings.getInstance(project).formatOnType = false
        type(')')
        assertTrue(requests.isEmpty())
    }

    fun testIgnoredFileReturnsNoEdits() {
        myFixture.tempDirFixture.createFile("ignored/biome.json", """{"root":true,"files":{"includes":["**","!**/index.js"]}}""")
        open("ignored/index.js", "const value=[1,2<caret>;")
        type(']')
        assertEquals("const value=[1,2];", myFixture.editor.document.text)
    }

    fun testFormatterDisabledReturnsNoEdits() {
        myFixture.tempDirFixture.createFile("disabled/biome.json", """{"root":true,"formatter":{"enabled":false}}""")
        open("disabled/index.js", "const value=[1,2<caret>;")
        type(']')
        assertEquals("const value=[1,2];", myFixture.editor.document.text)
    }

    fun testUnsupportedHtmlOnTypeReturnsNoEdits() {
        open("index.html", "<div>{<caret></div>")
        type('}')
        assertEquals("<div>{}</div>", myFixture.editor.document.text)
        assertEquals("}", requests.single().ch)
    }

    fun testSizeLimitReturnsNoEdits() {
        myFixture.tempDirFixture.createFile("limited/biome.json", """{"root":true,"files":{"maxSize":10}}""")
        open("limited/index.js", "const value=[1,2<caret>;")
        type(']')
        assertEquals("const value=[1,2];", myFixture.editor.document.text)
    }

    fun testNestedWorkspaceUsesItsOwnFormatterConfiguration() {
        myFixture.tempDirFixture.createFile("nested/biome.json", """{"root":true,"formatter":{"indentStyle":"space","indentWidth":4}}""")
        open("parent.js", "const value=1;<caret>")
        open("nested/index.js", "function answer(){return {value:42}<caret>")
        type('}')
        assertEquals("function answer() {\n    return { value: 42 };\n}", myFixture.editor.document.text)
        assertTrue(requests.single().textDocument.uri.endsWith("/nested/index.js"))
    }

    private fun open(path: String, marked: String) {
        val offset = marked.indexOf("<caret>")
        val file = myFixture.addFileToProject(path, marked.replace("<caret>", "")).virtualFile
        assertTrue(file.isInLocalFileSystem)
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.editor.caretModel.moveToOffset(offset)
        waitUntilFileOpenedByLspServer(project, file, BiomeLspServerDescriptor::class.java.name)
        PlatformTestUtil.waitWithEventsDispatching("Released server did not register closing-character triggers", {
            LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java).any {
                it.state == LspServerState.Running && it.initializeResult?.serverInfo?.version == "2.5.15" &&
                    it.descriptor.isSupportedFile(file) &&
                    (it.descriptor as BiomeLspServerDescriptor).onTypeFormatting.select(null, '}') != null
            }
        }, 15)
    }

    private fun type(character: Char) {
        myFixture.type(character)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        // The public handler attaches its job synchronously. Await every retained job
        // from this test-owned service scope; a wire response alone is not completion.
        scope.coroutineContext[Job]!!.children.toList().forEach(::await)
    }

    private fun await(job: Job) = PlatformTestUtil.waitWithEventsDispatching("Real on-type formatting did not finish", { job.isCompleted }, 15)
}
