package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.*
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.codeStyle.AbstractConvertLineSeparatorsAction
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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.LineSeparator
import java.util.*

class BiomeCheckRunner {
    private val LOG = thisLogger()
    fun run(project: Project, features: EnumSet<Feature>, documents: Array<Document>) {
        formatDocuments(
            project,
            features,
            documents.toList(),
            BiomeBundle.message(
                "biome.run.biome.check.with.features",
                features.joinToString(prefix = "(", postfix = ")") { it -> it.toString().lowercase() }
            )
        )
    }

    private fun formatDocuments(
        project: Project,
        features: EnumSet<Feature>,
        documents: List<Document>,
        commandDescription: String,
    ) {
        val runner = BiomeStdinRunner(project)
        val manager = FileDocumentManager.getInstance()
        val settings = BiomeSettings.getInstance(project)
        val biomePackage = BiomePackage(project)
        val requests = documents
            .mapNotNull { document -> manager.getFile(document)?.let { document to it } }
            .filter { GlobPatternUtil.isFileMatchingGlobPattern(project, settings.filePattern, it.second) }
            .map { BiomeRunner.Request(it.first, it.second, BiomeRunner.DEFAULT_TIMEOUT, commandDescription) }

        runCatching {
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, commandDescription, true) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.text = commandDescription

                        requests.forEach { request ->
                            val response = runner.check(request, features, biomePackage)

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
                val text = response.code
                val lineSeparator = StringUtil.detectSeparators(text)

                WriteCommandAction.writeCommandAction(project)
                    .withName(request.commandDescription)
                    .run<Exception> {
                        if (!StringUtil.equals(request.document.text, text)) {
                            request.document.setText(text)
                        }

                        setDetectedLineSeparator(
                            project,
                            request.virtualFile,
                            lineSeparator
                        )

                        FileDocumentManager.getInstance().saveDocument(request.document)
                    }
            }

            is BiomeRunner.Response.Failure -> {
                LOG.error("${response.title} - ${response.description}")
            }
        }
    }

    /**
     * [Taken from the JetBrains Prettier Plugin](https://github.com/JetBrains/intellij-plugins/blob/5673be79dd9e0fff7ed98e58a7d071a5a5f96d87/prettierJS/src/com/intellij/prettierjs/ReformatWithPrettierAction.java#L486)
     * [Apache License 2.0](https://github.com/JetBrains/intellij-plugins/blob/5673be79dd9e0fff7ed98e58a7d071a5a5f96d87/prettierJS/LICENSE.TXT)
     *
     *  @return true if the line separators were updated
     */
    private fun setDetectedLineSeparator(project: Project, vFile: VirtualFile, newSeparator: LineSeparator?): Boolean {
        if (newSeparator != null) {
            val newSeparatorString: String = newSeparator.separatorString

            if (!StringUtil.equals(vFile.detectedLineSeparator, newSeparatorString)) {
                AbstractConvertLineSeparatorsAction.changeLineSeparators(project, vFile, newSeparatorString)
                return true
            }
        }
        return false
    }
}
