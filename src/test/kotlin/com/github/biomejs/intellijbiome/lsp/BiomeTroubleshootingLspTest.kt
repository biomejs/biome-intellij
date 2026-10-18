package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.services.BiomeTroubleshootingService
import com.github.biomejs.intellijbiome.services.DefaultBiomeLspRequests
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeTroubleshootingLspTest : BiomeLspFixtureTestCase() {
    private var verifyAfterDisposal: (() -> Unit)? = null

    override fun tearDown() {
        val verify = verifyAfterDisposal
        verifyAfterDisposal = null
        try { super.tearDown() } finally { verify?.invoke() }
    }

    fun testReleasedServerRpcUsesCapturedSelectionAndLeavesOtherWorkspaceRunning() {
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        val root = myFixture.tempDirFixture.findOrCreateDir(".")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root)
        val executable = root.findFileByRelativePath("node_modules/@biomejs/biome/bin/biome")!!.path
        myFixture.tempDirFixture.createFile("biome.json", "{}")
        myFixture.tempDirFixture.createFile("nested/biome.json", "{\"root\":true}")
        val first = myFixture.tempDirFixture.createFile("index.js", "const first = 1;\n")
        val second = myFixture.tempDirFixture.createFile("nested/index.js", "const second = 2;\n")
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.AUTOMATIC
            executablePath = ""
            configPath = ""
        }
        FileEditorManager.getInstance(project).openFile(first, false)
        FileEditorManager.getInstance(project).openFile(second, false)
        val manager = LspServerManager.getInstance(project)
        fun servers() = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList()
        PlatformTestUtil.waitWithEventsDispatching("Two real Biome servers did not initialize", {
            servers().size == 2 && servers().all {
                it.state == LspServerState.Running && it.initializeResult?.serverInfo?.version == "2.5.15"
            }
        }, 20)
        val before = servers()
        val service = project.service<BiomeTroubleshootingService>()
        val selected = service.targets(second).single()
        assertEquals(second.parent, selected.descriptor.roots.single())
        assertEquals(BiomeLsp4jServer::class.java, selected.descriptor.lsp4jServerClass)
        // Settings can differ from a running descriptor; report the captured executable, not a fresh discovery.
        BiomeSettings.getInstance(project).executablePath = "/never/launch/this"
        val report = runWithModalProgressBlocking(project, "Test real troubleshooting") { service.collect(selected) }!!
        assertTrue(report.text, report.text.contains("Version: 2.5.15"))
        assertTrue(report.text, report.text.contains("Selected executable: $executable"))
        assertTrue(report.text, report.text.contains("Configured executable: /never/launch/this"))
        assertTrue(report.text, report.text.contains("Target root URI:"))
        assertTrue(report.text, report.text.contains("other active workspaces"))
        assertEquals(before, servers())
        assertTrue(servers().all { it.state == LspServerState.Running })
        val outer = service.targets(first).single().server
        val response = runWithModalProgressBlocking(project, "Test surviving workspace") { DefaultBiomeLspRequests.rage(outer) }
        assertNotNull("The other workspace must remain usable after collection", response)
        val text = response!!.toPlainText()
        assertTrue(text, text.contains("Version: 2.5.15"))
        assertEquals(before, servers())
    }

    fun testUnavailableCollectionDoesNotStartServerOrProbeExecutable() {
        val file = myFixture.tempDirFixture.createFile("index.js", "")
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = "/must/not/be/executed"
        }
        val manager = LspServerManager.getInstance(project)
        val service = project.service<BiomeTroubleshootingService>()
        assertTrue(service.targets(file).isEmpty())
        val report = service.localReport(file)
        assertTrue(report.text, report.text.contains("No running Biome server"))
        assertTrue(manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).isEmpty())
    }

    fun testDisposedProjectCannotDeliverCollectedInformation() {
        val service = project.service<BiomeTroubleshootingService>()
        val report = service.localReport(null)
        val disposedProject = project
        verifyAfterDisposal = {
            assertTrue(disposedProject.isDisposed)
            assertFalse(service.deliver(report) { fail("Disposed project must not open a preview") })
        }
    }
}
