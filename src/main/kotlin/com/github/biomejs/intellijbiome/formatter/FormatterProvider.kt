package com.github.biomejs.intellijbiome.formatter

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomeStdinRunner
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService.Feature
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.NotNull
import java.nio.charset.StandardCharsets
import java.util.*

class FormatterProvider : AsyncDocumentFormattingService() {
    override fun getFeatures(): MutableSet<Feature> = FEATURES
    override fun getNotificationGroupId(): String = NOTIFICATION_GROUP_ID
    override fun getName(): String = NAME
    override fun canFormat(file: PsiFile): Boolean {
        // IDEs with version >= 2023.3 uses native LSP formatter
        return ApplicationInfo.getInstance().build.baselineVersion < 233
    }

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val file = request.context.virtualFile ?: return null
        val project = request.context.project
        val formatterRunner = BiomeStdinRunner.getInstance(project)
        val settings = BiomeSettings.getInstance(project)

        if (!settings.canFormat(project, file)) {
            return null
        }

        try {
            val commandLine = formatterRunner.createCommandLine(file, "format")

            val handler = OSProcessHandler(commandLine.withCharset(StandardCharsets.UTF_8))
            return object : FormattingTask {
                override fun run() {
                    handler.addProcessListener(object : CapturingProcessAdapter() {
                        override fun processTerminated(@NotNull event: ProcessEvent) {
                            val exitCode = event.exitCode
                            if (exitCode == 0) {
                                request.onTextReady(output.stdout)
                            } else {
                                request.onError(BiomeBundle.message("biome.formatting.failure"), output.stderr)
                            }
                        }
                    })
                    handler.startNotify()
                }

                override fun cancel(): Boolean {
                    handler.destroyProcess()
                    return true
                }

                override fun isRunUnderProgress(): Boolean {
                    return true
                }
            }
        } catch (error: ExecutionException) {
            val message = error.message ?: ""
            request.onError(BiomeBundle.message("biome.formatting.failure"), message)
            return null
        }
    }

    companion object {
        val NAME: String = BiomeBundle.message("biome.formatting.service.name")
        const val NOTIFICATION_GROUP_ID = "Biome"
        val FEATURES: EnumSet<Feature> = EnumSet.noneOf(Feature::class.java)
    }
}
