package com.github.biomejs.intellijbiome

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.Nls
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

enum class Feature {
    Format,
    SafeFixes,
    UnsafeFixes
}

interface BiomeRunner {
    companion object {
        val DEFAULT_TIMEOUT: Duration = 30000.milliseconds
    }

    suspend fun check(request: Request): Response

    data class Request(
        val document: Document,
        val virtualFile: VirtualFile,
        val timeout: Duration,
        @NlsContexts.Command val commandDescription: String
    )

    sealed class Response {
        class Success(val code: String) : Response()

        class Failure(
            @Nls val title: String,
            @NlsSafe val description: String,
            val exitCode: Int?
        ) : Response()

    }
}
