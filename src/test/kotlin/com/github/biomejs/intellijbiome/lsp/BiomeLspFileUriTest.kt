package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import java.net.URI
import java.nio.file.Path

class BiomeLspFileUriTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    fun testUnmappedPathsRetainPlatformUriHandling() {
        for (path in listOf("/home/user/project/index.js", "C:/project/index.js", "//server/share/index.js")) {
            assertNull(targetFileUri(path, path))
        }
    }

    fun testMappedWslPathsUseLinuxFileUris() {
        for (host in listOf("wsl$", "wsl.localhost")) {
            assertEquals("file:///home/user/project/index.js",
                targetFileUri("//$host/Ubuntu/home/user/project/index.js", "/home/user/project/index.js"))
        }
    }

    fun testMappedPathEscapesReservedCharactersAndUnicode() {
        val path = "/home/user/café #100%?.js"
        val uri = checkNotNull(targetFileUri("//wsl.localhost/Ubuntu$path", path))
        assertEquals("file:///home/user/caf%C3%A9%20%23100%25%3F.js", uri)
        assertEquals(path, URI(uri).path)
        assertNull(URI(uri).query)
        assertNull(URI(uri).fragment)
    }

    fun testMappedPathDoesNotDecodeLiteralPercentEscapes() {
        val path = "/home/user/literal%20space.js"
        val uri = checkNotNull(targetFileUri("//wsl.localhost/Ubuntu$path", path))
        assertEquals("file:///home/user/literal%2520space.js", uri)
        assertEquals(path, URI(uri).path)
    }

    fun testLocalDocumentUriRoundTripsThroughDescriptor() {
        val file = myFixture.addFileToProject("dir #100%/café.js", "let value = 1;").virtualFile
        val descriptor = descriptor(file)
        val uri = descriptor.getFileUri(file)
        assertEquals("file", URI(uri).scheme)
        assertEquals(file.path, URI(uri).path)
        assertNull(URI(uri).fragment)
        assertEquals(file.path, descriptor.findFileByUri(uri)?.path)
    }

    fun testPlatformInitializationUsesTheDescriptorWorkspaceUri() {
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        val descriptor = descriptor(file)
        val root = descriptor.roots.single()
        val params = descriptor.createInitializeParams()
        val workspace = params.workspaceFolders.single()
        assertEquals(descriptor.getFileUri(root), workspace.uri)
        assertEquals(root.path, descriptor.findFileByUri(workspace.uri)?.path)
    }

    private fun descriptor(file: VirtualFile): LspClientDescriptor {
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            configPath = file.parent.path
        }
        var capturedDescriptor: LspClientDescriptor? = null
        BiomeLspServerSupportProvider().fileOpened(project, file, object : LspIntegrationProvider.LspClientStarter {
            override fun ensureClientStarted(descriptor: LspClientDescriptor) {
                capturedDescriptor = descriptor
            }
        })
        return checkNotNull(capturedDescriptor)
    }
}
