package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.WorkspaceFolder
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

    @Suppress("DEPRECATION")
    fun testMappedInitializationRetainsTargetRootAndWorkspaceUris() {
        val localRoot = "//wsl.localhost/Ubuntu/home/user/my project"
        val targetRoot = "/home/user/my project"
        val targetUri = checkNotNull(targetFileUri(localRoot, targetRoot))
        val params = InitializeParams().apply {
            rootPath = localRoot
            rootUri = targetUri
            workspaceFolders = listOf(WorkspaceFolder(targetUri, "my project"))
        }.withTargetRootPath(localRoot) { path ->
            assertEquals(localRoot, path)
            targetRoot
        }
        assertEquals(targetRoot, params.rootPath)
        assertEquals(targetUri, params.rootUri)
        assertEquals(targetUri, params.workspaceFolders.single().uri)
        assertEquals(params.rootPath, URI(params.rootUri).path)
    }

    @Suppress("DEPRECATION")
    fun testUnmappedInitializationPreservesPlatformRootPath() {
        val params = InitializeParams().apply { rootPath = "/eel/project" }
            .withTargetRootPath("/local/project") { it }
        assertEquals("/eel/project", params.rootPath)
    }

    @Suppress("DEPRECATION")
    fun testInitializationWithoutSingleRootDoesNotMapAPath() {
        val params = InitializeParams().withTargetRootPath(null) { error("No single root to map") }
        assertNull(params.rootPath)
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

    @Suppress("DEPRECATION")
    fun testInitializationUsesTheSameRootForPathsAndUris() {
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        val descriptor = descriptor(file)
        val root = descriptor.roots.single()
        val params = descriptor.createInitializeParams()
        assertEquals(root.path, params.rootPath)
        assertEquals(descriptor.getFileUri(root), params.rootUri)
        assertEquals(params.rootUri, params.workspaceFolders.single().uri)
        assertEquals(root.path, descriptor.findFileByUri(params.rootUri)?.path)
    }

    private fun descriptor(file: VirtualFile): LspServerDescriptor {
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            configPath = file.parent.path
        }
        var capturedDescriptor: LspServerDescriptor? = null
        BiomeLspServerSupportProvider().fileOpened(project, file, object : LspServerSupportProvider.LspServerStarter {
            override fun ensureServerStarted(descriptor: LspServerDescriptor) {
                capturedDescriptor = descriptor
            }
        })
        return checkNotNull(capturedDescriptor)
    }
}
