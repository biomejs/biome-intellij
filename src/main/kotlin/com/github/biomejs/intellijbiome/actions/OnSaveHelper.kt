package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.BiomeRunner
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.lang.javascript.linter.GlobPatternUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

class OnSaveHelper {
    companion object {
        val LOG = thisLogger()
    }

    fun formatDocuments(
        project: Project,
        documents: List<Document>,
        commandDescription: String,
        callback: (request: BiomeRunner.Request) -> BiomeRunner.Response
    ) {
        val manager = FileDocumentManager.getInstance()
        val settings = BiomeSettings.getInstance(project)
        val requests = documents
            .mapNotNull { document -> manager.getFile(document)?.let { document to it } }
            .filter { GlobPatternUtil.isFileMatchingGlobPattern(project, settings.formatFilePattern, it.second) }
            .map { BiomeRunner.Request(it.first, it.second, BiomeRunner.DEFAULT_TIMEOUT, commandDescription) }

        runCatching {
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, commandDescription, true) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.text = commandDescription

                        requests.forEach { request ->
                            val response = callback(request)

                            if (!indicator.isCanceled) {
                                applyChanges(project, request, response)
                            }
                        }
                    }
                }
            )
        }.onFailure { exception ->
            when (exception) {
                is ProcessCanceledException -> {}

                else -> {
                    LOG.error(exception)
                }
            }
        }
    }

    private fun applyChanges(
        project: Project,
        request: BiomeRunner.Request,
        response: BiomeRunner.Response
    ) {
        when (response) {
            is BiomeRunner.Response.Success -> {
                WriteCommandAction.writeCommandAction(project)
                    .withName(request.commandDescription)
                    .run<Exception> { request.document.setText(response.code) }
            }

            is BiomeRunner.Response.Failure -> {
                LOG.error(response.title)
            }
        }
    }

}
