package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import java.nio.file.Files
import java.nio.file.Path

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeQuickFixPreviewTest : BiomeLspFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        setUpLspFixture("unused-function")
        val executableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "biome.cmd" else "biome"
        val executable = Path.of(myFixture.tempDirPath, "node_modules", ".bin", executableName)
        check(Files.isRegularFile(executable)) { "Locked Biome 2.5.15 fixture executable is required" }
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = executable.toString()
        }
    }

    fun testUnusedParameterPreviewApplyAndUndo() {
        val events = RuntimeGateEvents(project, testRootDisposable)
        val file = checkNotNull(myFixture.findFileInTempDir("index.js"))
        myFixture.configureFromExistingVirtualFile(file)
        waitUntilFileOpenedByLspServer(project, file, "com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor")
        events.awaitDiagnostics(file, version = "2.5.15")

        val document = myFixture.editor.document
        val original = "function broken(param) {}\n"
        val expected = "function broken(_param) {}\n"
        assertEquals(original, document.text)
        myFixture.editor.caretModel.moveToOffset(original.indexOf("param"))
        val intentions = myFixture.availableIntentions
        val action = checkNotNull(intentions.singleOrNull {
            it.text == "If this is intentional, prepend param with an underscore."
        }) { "Expected the Biome parameter fix among ${intentions.map { it.text }}" }

        assertEquals(expected, myFixture.getIntentionPreviewText(action))
        assertEquals("Preview must leave the physical document unchanged", original, document.text)
        myFixture.launchAction(action)
        assertEquals(expected, document.text)

        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = UndoManager.getInstance(project)
        assertTrue("The native quick fix must be undoable", undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals(original, document.text)
    }
}
