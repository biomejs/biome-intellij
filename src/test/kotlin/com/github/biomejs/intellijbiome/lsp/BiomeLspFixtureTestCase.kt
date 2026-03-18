package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.components.service
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

abstract class BiomeLspFixtureTestCase :
    CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {

    protected fun setUpLspFixture(fixtureName: String) {
        (myFixture as CodeInsightTestFixtureImpl).canChangeDocumentDuringHighlighting(true)
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        copyFixtureFiles(fixtureName)
        val projectDir = myFixture.tempDirFixture.getFile(".") ?: error("Project directory not found")
        TestNpmPackageInstaller(myFixture).installForTest(this::class.java, projectDir)
    }

    override fun setUp() {
        super.setUp()
        resetBiomeSettings()
    }

    override fun tearDown() {
        try {
            project.service<BiomeServerService>().stopBiomeServer()
        } finally {
            super.tearDown()
        }
    }

    private fun resetBiomeSettings() {
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.AUTOMATIC
        settings.configPath = ""
        settings.executablePath = ""
    }

    private fun copyFixtureFiles(fixtureName: String) {
        val fixtureDir = Path.of(myFixture.testDataPath, fixtureName)
        require(Files.isDirectory(fixtureDir)) {
            "Fixture directory not found: $fixtureDir"
        }

        Files.walk(fixtureDir).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { !shouldSkipFixtureFile(fixtureDir.relativize(it)) }
                .forEach { file ->
                    val relativePath = fixtureDir.relativize(file).invariantSeparatorsPathString
                    myFixture.copyFileToProject("$fixtureName/$relativePath", relativePath)
                }
        }
    }

    private fun shouldSkipFixtureFile(relativePath: Path): Boolean {
        if (relativePath.any { it.toString() == "_package-locks-store" }) {
            return true
        }

        val fileName = relativePath.fileName.toString()
        return fileName.contains(".expected.")
    }
}
