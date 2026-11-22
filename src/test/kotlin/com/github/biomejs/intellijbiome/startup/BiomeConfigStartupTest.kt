package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.lsp.BiomeLspFixtureTestCase
import com.github.biomejs.intellijbiome.lsp.RuntimeGateEvents
import com.github.biomejs.intellijbiome.lsp.configureBiomeForLspTests
import com.github.biomejs.intellijbiome.lsp.saveAndAwaitCompletion
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.openapi.command.WriteCommandAction

@TestNpmPackage("@biomejs/biome@2.5.15")
class Biome2515ConfigStartupTest : BiomeConfigStartupTestCase()

@TestNpmPackage("@biomejs/biome@2.6.7")
class Biome267ConfigStartupTest : BiomeConfigStartupTestCase()

abstract class BiomeConfigStartupTestCase : BiomeLspFixtureTestCase() {
    fun testStartupFormatsWithTheNestedWorkspaceConfiguration() {
        setUpLspFixture("save-actions")
        myFixture.addFileToProject(
            "nested/biome.json",
            """{"root":true,"javascript":{"formatter":{"quoteStyle":"single"}}}""",
        )
        val file = myFixture.addFileToProject("nested/first.js", "let value = 1;\n").virtualFile
        myFixture.configureBiomeForLspTests()
        BiomeSettings.getInstance(project).formatOnSave = true

        val events = RuntimeGateEvents(project, testRootDisposable)
        myFixture.configureFromExistingVirtualFile(file)
        val version = javaClass.getAnnotation(TestNpmPackage::class.java).packageSpec.substringAfterLast('@')
        events.awaitDiagnostics(file, version)

        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.setText("const message=\"hello\";\n") }
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertEquals("const message = 'hello';\n", document.text)
    }
}
