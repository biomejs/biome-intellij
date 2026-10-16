package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.launcher.createBiomeProcessDirectory
import com.github.biomejs.intellijbiome.launcher.isolatedBiomeEnvironment
import com.github.biomejs.intellijbiome.launcher.setTestEnvironment
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.util.EnvironmentUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

abstract class BiomeLspFixtureTestCase :
    CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private var originalProcessEnvironment: Map<String, String>? = null
    private var processDirectory: Path? = null

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
        if (SystemInfoRt.isMac || SystemInfoRt.isLinux) {
            val original = EnvironmentUtil.getEnvironmentMap().toMap()
            originalProcessEnvironment = original
            val directory = createBiomeProcessDirectory("bl-")
            processDirectory = directory
            setTestEnvironment(isolatedBiomeEnvironment(original, directory))
        }
    }

    override fun tearDown() {
        try {
            if (!project.isDisposed) {
                resetBiomeSettings()
                project.service<BiomeServerService>().stopBiomeServer()
            }
        } finally {
            try {
                super.tearDown()
            } finally {
                try {
                    originalProcessEnvironment?.let(::setTestEnvironment)
                    originalProcessEnvironment = null
                } finally {
                    processDirectory?.let { FileUtil.delete(it.toFile()) }
                    processDirectory = null
                }
            }
        }
    }

    private fun resetBiomeSettings() {
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.AUTOMATIC
        settings.configPath = ""
        settings.executablePath = ""
        settings.formatOnSave = false
        settings.sortImportOnSave = false
        settings.applySafeFixesOnSave = false
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
