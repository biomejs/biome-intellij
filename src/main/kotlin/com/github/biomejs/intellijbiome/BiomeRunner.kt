package com.github.biomejs.intellijbiome

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.Nls
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

enum class Feature {
    Format,
    SafeFixes,
    UnsafeFixes
}

interface BiomeRunner {
    companion object {
        val DEFAULT_TIMEOUT = 30000.milliseconds
    }

    fun check(request: Request, features: EnumSet<Feature>): Response
    fun createCommandLine(file: VirtualFile, action: String, args: List<String> = listOf()): GeneralCommandLine


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


