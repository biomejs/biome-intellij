package com.github.biomejs.intellijbiome.schema

import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.lsp.BiomeLspFixtureTestCase
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.lsp.configureLocalNodeForRuntimeTests
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeSchemaSelectionLspTest : BiomeLspFixtureTestCase() {
    fun testRunningSelectionWinsOverBaseDiscoveryAndRejectsVersionMismatch() {
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        val root = myFixture.tempDirFixture.findOrCreateDir(".")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root)
        val nested = myFixture.tempDirFixture.findOrCreateDir("nested")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root, "nested")
        val rootSchema = root.findFileByRelativePath("node_modules/@biomejs/biome/configuration_schema.json")!!
        val nestedSchema = nested.findFileByRelativePath("node_modules/@biomejs/biome/configuration_schema.json")!!
        assertFalse(rootSchema.canonicalPath == nestedSchema.canonicalPath)
        val config = myFixture.tempDirFixture.createFile("config/custom.jsonc", "{}")
        val source = myFixture.tempDirFixture.createFile("nested/index.js", "const value = 1;\n")
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.configPath = config.path
        settings.executablePath = ""
        settings.supportedExtensions = mutableListOf(".js")
        myFixture.configureFromExistingVirtualFile(config)
        val associations = project.service<BiomeSchemaAssociationService>()
        waitUntil("No package schema before LSP startup") { associations.schemaFor(config)?.canonicalPath == rootSchema.canonicalPath }
        val selectedExecutable = BiomePackage(project).binaryPath(source)
        FileEditorManager.getInstance(project).openFile(source, false)
        val manager = LspServerManager.getInstance(project)
        waitUntil("Real Biome server did not initialize") {
            manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).any {
                it.state == LspServerState.Running && it.initializeResult?.serverInfo?.version == "2.5.15"
            }
        }
        var server = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
        val descriptor = server.descriptor as BiomeLspServerDescriptor
        assertEquals(selectedExecutable, descriptor.selectedExecutable)
        assertEquals(config.path, descriptor.configurationPath)
        assertEquals(listOf(root), descriptor.roots.toList())
        waitUntil("Running descriptor must select the nested package schema") { associations.schemaFor(config)?.canonicalPath == nestedSchema.canonicalPath }

        val originalServer = server
        project.service<BiomeServerService>().stopBiomeServer()
        waitUntil("Stopped selection must no longer supply the schema") {
            server.state != LspServerState.Running && associations.schemaFor(config) == null
        }
        assertTrue(manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).none { it.state == LspServerState.Running })
        project.service<BiomeServerService>().restartBiomeServer()
        waitUntil("Replacement initialization must restore the selected package schema") {
            manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).any {
                it !== originalServer && it.state == LspServerState.Running && it.initializeResult?.serverInfo?.version == "2.5.15"
            } && associations.schemaFor(config)?.canonicalPath == nestedSchema.canonicalPath
        }
        server = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
        assertEquals(selectedExecutable, (server.descriptor as BiomeLspServerDescriptor).selectedExecutable)

        val metadata = nestedSchema.parent.findChild("package.json")!!
        val original = VfsUtil.loadText(metadata)
        WriteCommandAction.runWriteCommandAction(project) {
            VfsUtil.saveText(metadata, original.replace("\"version\": \"2.5.15\"", "\"version\": \"0.0.0\""))
        }
        assertTrue(VfsUtil.loadText(metadata).contains("0.0.0"))
        val changed = associations.invalidate()
        waitUntil("Version mismatch was not re-evaluated") { changed.isCompleted }
        assertNull("Do not pair a running 2.5.15 server with another package schema", associations.schemaFor(config))
        assertEquals("2.5.15", server.initializeResult?.serverInfo?.version)
        assertEquals(listOf(server), manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList())
    }

    private fun waitUntil(message: String, condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching(message, condition, 20)
}
