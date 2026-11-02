package com.github.biomejs.intellijbiome.lsp

import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal fun configureLocalNodeForRuntimeTests(project: Project, disposable: Disposable) {
    val node = System.getenv("PATH").split(File.pathSeparator).asSequence()
        .map { Path.of(it, "node") }.firstOrNull { Files.isExecutable(it) }
        ?: error("Node is required for real automatic-mode lifecycle tests")
    NodeJsInterpreterManager.getInstance(project).setInterpreterRef(
        NodeJsInterpreterRef.create(NodeJsLocalInterpreter(node.toString())), disposable)
}

/** Subscribe before opening any editor so a fast initial diagnostics notification cannot be missed. */
internal class RuntimeGateEvents(project: Project, disposable: Disposable) {
    private val diagnostics = java.util.concurrent.CopyOnWriteArrayList<Pair<com.intellij.platform.lsp.api.LspClient, com.intellij.openapi.vfs.VirtualFile>>()

    init {
        com.intellij.platform.lsp.api.LspClientManager.getInstance(project).addListener(
            object : com.intellij.platform.lsp.api.LspClientManagerListener {
                override fun diagnosticsReceived(lspClient: com.intellij.platform.lsp.api.LspClient, file: com.intellij.openapi.vfs.VirtualFile) {
                    if (lspClient.providerClass == BiomeLspServerSupportProvider::class.java) diagnostics.add(lspClient to file)
                }
            }, disposable, true)
    }

    fun awaitDiagnostics(file: com.intellij.openapi.vfs.VirtualFile, version: String? = null, timeout: Int = 20) {
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
            "Biome diagnostics not received for ${file.path} (version=$version)",
            { diagnostics.any { it.second == file && (version == null || it.first.initializeResult?.serverInfo?.version == version) } }, timeout)
    }
    fun awaitDiagnostics(file: com.intellij.openapi.vfs.VirtualFile, server: com.intellij.platform.lsp.api.LspClient) {
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
            "Replacement Biome server did not send diagnostics for ${file.path}",
            { diagnostics.any { it.first === server && it.second == file } }, 20)
    }

}
