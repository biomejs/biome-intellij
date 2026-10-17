package com.github.biomejs.intellijbiome.lsp

import com.intellij.util.messages.Topic
import org.eclipse.lsp4j.PublishDiagnosticsParams

/** Notifications observed through the descriptor's supported client and lifecycle hooks. */
internal interface BiomeLspDocumentListener {
    fun diagnosticsPublished(descriptor: BiomeLspServerDescriptor, params: PublishDiagnosticsParams) {}
    fun serverStopped(descriptor: BiomeLspServerDescriptor) {}

    companion object {
        val TOPIC: Topic<BiomeLspDocumentListener> =
            Topic.create("Biome LSP document notifications", BiomeLspDocumentListener::class.java)
    }
}
