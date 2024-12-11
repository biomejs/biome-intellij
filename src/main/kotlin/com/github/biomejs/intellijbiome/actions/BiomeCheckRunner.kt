package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomeRunner
import com.github.biomejs.intellijbiome.BiomeStdinRunner
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.codeStyle.AbstractConvertLineSeparatorsAction
import com.intellij.lang.javascript.linter.GlobPatternUtil
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.LineSeparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeout

class BiomeCheckRunner(
    val project: Project,
) {
    private val LOG = thisLogger()
    private val settings = BiomeSettings.getInstance(project)
    private val description: String by lazy {
        BiomeBundle.message("biome.run.biome.check.with.features",
            settings.getEnabledFeatures().joinToString(prefix = "(", postfix = ")") { it -> it.toString().lowercase() })
    }

    fun run(documents: Array<Document>) {
        runWithModalProgressBlocking(
            project,
            BiomeBundle.message(
                "biome.run.biome.check.with.features",
                settings.getEnabledFeatures().joinToString(prefix = "(", postfix = ")") { it -> it.toString().lowercase() }
            )
        ) {
            withTimeout(5_000) {
                val jobs = documents.map {
                    async(Dispatchers.IO) {
                        processDocument(it)
                    }
                }
                jobs.awaitAll()
            }
        }
    }

    suspend fun processDocument(document: Document) {
        val request = getRequest(document) ?: return
        val runner = BiomeStdinRunner(project)
        val response = runner.check(request)
        applyChanges(request, response)
    }

    private suspend fun getRequest(document: Document): BiomeRunner.Request? {
        return readAction {
            val manager = FileDocumentManager.getInstance()
            val file = manager.getFile(document) ?: return@readAction null

            if (!GlobPatternUtil.isFileMatchingGlobPattern(project, settings.filePattern, file)) {
                return@readAction null
            }

            val request = BiomeRunner.Request(document, file, BiomeRunner.DEFAULT_TIMEOUT, description)
            return@readAction request
        }
    }

    private suspend fun applyChanges(
        request: BiomeRunner.Request,
        response: BiomeRunner.Response,
    ) {
        when (response) {
            is BiomeRunner.Response.Success -> {
                val text = response.code
                val lineSeparator = StringUtil.detectSeparators(text)
                // internally we keep newlines as \n
                val normalizedText = StringUtil.convertLineSeparators(text)
                writeCommandAction(project, request.commandDescription) {
                    if (!StringUtil.equals(request.document.charsSequence, normalizedText)) {
                        request.document.setText(normalizedText)
                    }

                    setDetectedLineSeparator(project, request.virtualFile, lineSeparator)
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
    private fun setDetectedLineSeparator(
        project: Project,
        vFile: VirtualFile,
        newSeparator: LineSeparator?,
    ): Boolean {
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
