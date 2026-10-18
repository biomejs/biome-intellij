package com.github.biomejs.intellijbiome.lsp

import org.eclipse.lsp4j.services.LanguageServer as Lsp4jServer
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

interface BiomeLsp4jServer : Lsp4jServer {
    @JsonRequest("biome/rage")
    fun rage(params: BiomeRageParams): CompletableFuture<BiomeRageResult>
}

class BiomeRageParams
