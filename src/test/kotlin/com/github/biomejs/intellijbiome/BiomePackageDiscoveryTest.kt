package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.launcher.setTestEnvironment
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.lsp.BiomeLspWorkspaceSettings
import com.github.biomejs.intellijbiome.lsp.configureLocalNodeForRuntimeTests
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutput
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.util.EnvironmentUtil
import org.eclipse.lsp4j.ConfigurationItem
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BiomePackageDiscoveryTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private val settings get() = BiomeSettings.getInstance(project)

    override fun setUp() {
        super.setUp()
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.executablePath = ""
        settings.configPath = ""
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
    }

    fun testManualConfigOnlyDiscoversPackageAndPreservesWorkspaceOverride() {
        val launcher = createPackage()
        val selectedConfig = myFixture.addFileToProject("config/custom.jsonc", "{}").virtualFile
        val file = myFixture.addFileToProject("src/index.js", "let value = 1;").virtualFile
        settings.configPath = selectedConfig.path

        assertEquals(launcher.path, BiomePackage(project).binaryPath(file))
        val descriptors = mutableListOf<LspServerDescriptor>()
        BiomeLspServerSupportProvider().fileOpened(project, file, object : LspServerSupportProvider.LspServerStarter {
            override fun ensureServerStarted(descriptor: LspServerDescriptor) { descriptors += descriptor }
        })
        assertEquals(1, descriptors.size)
        val configuration = descriptors.single().getWorkspaceConfiguration(ConfigurationItem().apply { section = "biome" })
            as BiomeLspWorkspaceSettings
        assertEquals(selectedConfig.path, configuration.configurationPath)
        assertEquals("", settings.executablePath)
        assertEquals(selectedConfig.path, settings.configPath)
    }

    fun testMissingPackageReturnsNoExecutableForConfigOnlySetup() {
        val file = myFixture.addFileToProject("src/index.js", "let value = 1;").virtualFile
        settings.configPath = myFixture.addFileToProject("config/custom.jsonc", "{}").virtualFile.path

        assertNull(BiomePackage(project).binaryPath(file))
        assertEquals("", settings.executablePath)
    }

    fun testManualDiscoverySelectsNearestPackageForEachFile() {
        val rootLauncher = createPackage()
        val nestedLauncher = createPackage("packages/nested")
        val rootFile = myFixture.addFileToProject("src/index.js", "").virtualFile
        val nestedFile = myFixture.addFileToProject("packages/nested/src/index.js", "").virtualFile
        val siblingFile = myFixture.addFileToProject("packages/sibling/src/index.js", "").virtualFile
        val biome = BiomePackage(project)

        assertEquals(nestedLauncher.path, biome.binaryPath(nestedFile))
        assertEquals(rootLauncher.path, biome.binaryPath(rootFile))
        assertEquals(rootLauncher.path, biome.binaryPath(siblingFile))
    }

    fun testWhitespaceExecutableUsesDiscoveryWithoutChangingSavedValue() {
        val launcher = createPackage()
        val file = myFixture.addFileToProject("index.js", "").virtualFile
        for (blank in listOf(" ", "\t\n", " \t\r\n ", "\u2003")) {
            settings.executablePath = blank
            assertEquals(launcher.path, BiomePackage(project).binaryPath(file))
            assertEquals(blank, settings.executablePath)
        }
    }

    fun testExplicitManualExecutableWinsWithoutTrimmingOrValidation() {
        createPackage()
        val file = myFixture.addFileToProject("index.js", "").virtualFile
        for (selected in listOf("./custom biome", "/missing/biome", "biome", " /custom/biome ", "\\\\server\\share\\biome")) {
            settings.executablePath = selected
            assertEquals(selected, BiomePackage(project).binaryPath(file))
        }
    }

    fun testAutomaticModeIgnoresStoredManualOverrides() {
        val launcher = createPackage()
        val file = myFixture.addFileToProject("index.js", "").virtualFile
        settings.configurationMode = ConfigurationMode.AUTOMATIC
        settings.executablePath = "/custom/biome"
        settings.configPath = "custom.jsonc"

        assertEquals(launcher.path, BiomePackage(project).binaryPath(file))
        assertNull(BiomePackage(project).configPath())
    }

    fun testDisabledModeDoesNotResolveInstalledOrExplicitExecutable() {
        createPackage()
        val file = myFixture.addFileToProject("index.js", "").virtualFile
        settings.configurationMode = ConfigurationMode.DISABLED
        settings.configPath = "custom.jsonc"
        for (selected in listOf("", "/custom/biome")) {
            settings.executablePath = selected
            assertNull(BiomePackage(project).binaryPath(file))
            assertNull(BiomePackage(project).configPath())
        }
    }

    fun testDiscoveredNpmLauncherUsesConfiguredNodeWithoutRequiringShebang() {
        val launcher = createPackage(source = "console.log('configured Node: ' + process.execPath);\n")
        val manager = NodeJsInterpreterManager.getInstance(project)
        val node = Path.of((manager.interpreter as NodeJsLocalInterpreter).interpreterSystemIndependentPath).toRealPath()
        val configuredNode = Files.createSymbolicLink(Path.of(myFixture.tempDirPath, "configured node with spaces"), node)
        manager.setInterpreterRef(NodeJsInterpreterRef.create(NodeJsLocalInterpreter(configuredNode.toString())))
        val originalEnvironment = EnvironmentUtil.getEnvironmentMap().toMap()
        val emptyPath = Files.createDirectory(Path.of(myFixture.tempDirPath, "path without node"))
        setTestEnvironment(originalEnvironment + ("PATH" to emptyPath.toString()))
        try {
            val run = BiomeTargetRunBuilder(project).getBuilder(launcher.path, launcher.parent.path).build()
            assertTrue("Discovered npm launchers must use the configured interpreter", run is BiomeTargetRun.Node)
            assertEquals("configured Node: $node", output(run))
        } finally {
            setTestEnvironment(originalEnvironment)
        }
    }

    private fun createPackage(
        directory: String = "",
        source: String = "#!/usr/bin/env node\nconsole.log('fixture');\n",
    ): VirtualFile {
        val prefix = directory.takeIf(String::isNotEmpty)?.let { "$it/" } ?: ""
        myFixture.addFileToProject("${prefix}package.json", """
            {"name":"discovery-fixture","private":true,"dependencies":{"@biomejs/biome":"2.5.15"}}
        """.trimIndent())
        myFixture.addFileToProject("${prefix}node_modules/@biomejs/biome/package.json", """
            {"name":"@biomejs/biome","version":"2.5.15","bin":{"biome":"bin/biome"}}
        """.trimIndent())
        return myFixture.addFileToProject("${prefix}node_modules/@biomejs/biome/bin/biome", source).virtualFile
    }

    private fun output(run: BiomeTargetRun): String {
        val handler = run.startProcess()
        try {
            val completion = CompletableFuture<ProcessOutput>()
            handler.addProcessListener(object : CapturingProcessAdapter() {
                override fun processTerminated(event: ProcessEvent) {
                    super.processTerminated(event)
                    completion.complete(output)
                }
            })
            handler.startNotify()
            val result = completion.get(10, TimeUnit.SECONDS)
            assertEquals(result.stderr, 0, result.exitCode)
            return result.stdout.trimEnd('\r', '\n')
        } finally {
            if (handler.process.isAlive) handler.destroyProcess()
            if (!handler.waitFor(2_000)) {
                handler.process.destroyForcibly()
                check(handler.waitFor(2_000)) { "Discovery launcher child did not terminate" }
            }
        }
    }
}
