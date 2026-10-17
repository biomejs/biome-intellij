package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import java.nio.file.Files
import java.nio.file.Path

class BiomeStartupEligibilityTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private lateinit var invocationLog: Path
    private val descriptors = mutableListOf<LspServerDescriptor>()
    private val starter = object : LspServerSupportProvider.LspServerStarter {
        override fun ensureServerStarted(descriptor: LspServerDescriptor) { descriptors += descriptor }
    }

    override fun setUp() {
        super.setUp()
        invocationLog = Path.of(myFixture.tempDirPath, "invocations.log")
        val executable = Path.of(myFixture.tempDirPath, "biome-probe")
        Files.writeString(executable, "#!/bin/sh\nprintf '%s\\n' \"\$*\" >> '${invocationLog}'\nprintf 'Version: 2.2.3\\n'\n")
        check(executable.toFile().setExecutable(true))
        myFixture.addFileToProject("biome.json", "{}")
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = executable.toString()
            configPath = ""
        }
    }

    fun testUnsupportedFileDoesNotProbeOrRequestServerStart() {
        val file = myFixture.addFileToProject("notes.unsupported", "plain text").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        assertFalse("Unsupported files must not invoke the executable", Files.exists(invocationLog))
        assertEmpty(descriptors)
    }

    fun testDisabledPluginDoesNotProbeOrRequestServerStart() {
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.DISABLED
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        assertFalse("Disabled projects must not invoke the executable", Files.exists(invocationLog))
        assertEmpty(descriptors)
    }

    fun testSupportedFileRequestsServerStart() {
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        assertEquals("Supported files still request a server", 1, descriptors.size)
    }
    @Suppress("DEPRECATION")
    fun testDisposedProjectDoesNotRequestServerStart() {
        val directory = Files.createTempDirectory("biome-eligibility-disposed")
        val disposedProject = com.intellij.openapi.project.ProjectManager.getInstance()
            .createProject("disposed-biome", directory.toString())!!
        try {
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
                com.intellij.openapi.util.Disposer.dispose(disposedProject)
            }
            val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
            BiomeLspServerSupportProvider().fileOpened(disposedProject, file, starter)
            assertEmpty(descriptors)
            assertFalse(Files.exists(invocationLog))
        } finally {
            if (!disposedProject.isDisposed) com.intellij.openapi.util.Disposer.dispose(disposedProject)
            com.intellij.openapi.util.io.FileUtil.delete(directory.toFile())
        }
    }

}
