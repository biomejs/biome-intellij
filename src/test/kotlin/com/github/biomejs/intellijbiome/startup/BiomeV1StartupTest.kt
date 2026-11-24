package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.launcher.createBiomeProcessDirectory
import com.github.biomejs.intellijbiome.launcher.isolatedBiomeEnvironment
import com.github.biomejs.intellijbiome.launcher.setTestEnvironment
import com.github.biomejs.intellijbiome.lsp.BiomeLspFixtureTestCase
import com.github.biomejs.intellijbiome.lsp.RuntimeGateEvents
import com.github.biomejs.intellijbiome.lsp.configureBiomeForLspTests
import com.github.biomejs.intellijbiome.lsp.configureLocalNodeForRuntimeTests
import com.github.biomejs.intellijbiome.lsp.saveAndAwaitCompletion
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.EnvironmentUtil
import java.nio.file.Path

@TestNpmPackage("@biomejs/biome@1.6.4")
class BiomeV164StartupTest : BiomeV1StartupTestCase()

@TestNpmPackage("@biomejs/biome@1.9.4")
class BiomeV194StartupTest : BiomeV1StartupTestCase()

abstract class BiomeV1StartupTestCase : BiomeLspFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        setUpLspFixture("unused-function")

        // Biome 1 reads its config override when the shared daemon starts.
        val originalEnvironment = EnvironmentUtil.getEnvironmentMap().toMap()
        val cache = createBiomeProcessDirectory("bv1-")
        Disposer.register(testRootDisposable) {
            setTestEnvironment(originalEnvironment)
            FileUtil.delete(cache.toFile())
        }
        setTestEnvironment(isolatedBiomeEnvironment(originalEnvironment - "BIOME_CONFIG_PATH", cache))
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
    }

    fun testAutomaticStartupFormatsTheOpenedFile() {
        assertFormatsWithQuotes('"')
    }

    fun testManualStartupUsesSelectedConfigurationFile() {
        val config = addSingleQuoteConfiguration()
        myFixture.configureBiomeForLspTests(config.path)
        assertFormatsWithQuotes('\'')
    }

    fun testManualStartupUsesSelectedConfigurationDirectory() {
        val config = addSingleQuoteConfiguration()
        myFixture.configureBiomeForLspTests(config.parent.name)
        BiomeSettings.getInstance(project).executablePath =
            Path.of(myFixture.tempDirPath, "node_modules/@biomejs/biome/bin/biome").toString()
        assertFormatsWithQuotes('\'')
    }

    private fun addSingleQuoteConfiguration() = myFixture.addFileToProject(
        "separate settings/biome.json",
        """{"javascript":{"formatter":{"quoteStyle":"single"}}}""",
    ).virtualFile

    private fun assertFormatsWithQuotes(quote: Char) {
        val file = myFixture.findFileInTempDir("index.js") ?: error("Missing fixture index.js")
        val events = RuntimeGateEvents(project, testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        myFixture.configureFromExistingVirtualFile(file)
        val version = javaClass.getAnnotation(TestNpmPackage::class.java).packageSpec.substringAfterLast('@')
        events.awaitDiagnostics(file, version)

        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.setText("const message=\"hello\";\n") }
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertEquals("const message = ${quote}hello${quote};\n", document.text)
    }
}
