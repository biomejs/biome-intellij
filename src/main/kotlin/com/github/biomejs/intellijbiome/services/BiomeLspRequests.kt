package com.github.biomejs.intellijbiome.services

import com.intellij.platform.lsp.api.LspServer
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

internal interface BiomeLspRequests {
    suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>>?
    suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit>?
}

internal object DefaultBiomeLspRequests : BiomeLspRequests {
    override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>>? =
        server.sendRequest { it.textDocumentService.codeAction(params) }

    override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit>? =
        server.sendRequest { it.textDocumentService.formatting(params) }
}
