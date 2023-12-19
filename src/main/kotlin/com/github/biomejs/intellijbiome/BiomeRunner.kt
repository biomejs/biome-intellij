package com.github.biomejs.intellijbiome

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.Nls
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

interface BiomeRunner {
    companion object {
        val DEFAULT_TIMEOUT = 30000.milliseconds
    }

    fun format(request: Request): Response
    fun applySafeFixes(request: Request): Response
    fun applyUnsafeFixes(request: Request): Response
    fun createCommandLine(file: VirtualFile, action: String, args: String? = null): GeneralCommandLine


    data class Request(
        val document: Document,
        val virtualFile: VirtualFile,
        val timeout: Duration,
        val commandDescription: String
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


