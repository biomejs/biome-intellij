package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import java.util.EnumSet

class BiomeCheckOnSaveAction internal constructor(
    private val execute: suspend (Project, Document, EnumSet<BiomeServerService.Feature>) -> Unit,
) : ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave() {
    constructor() : this({ project, document, features ->
        BiomeServerService.getInstance(project).executeFeatures(document, features)
    })

    override val presentableName: String
        get() = BiomeBundle.message("biome.save.action.name")

    override fun isEnabledForProject(project: Project): Boolean =
        BiomeSettings.getInstance(project).getEnabledFeatures().isNotEmpty()

    override suspend fun updateDocument(project: Project, document: Document) {
        val snapshot = readAction {
            val settings = BiomeSettings.getInstance(project)
            val features = settings.getEnabledFeatures()
            val file = FileDocumentManager.getInstance().getFile(document)
            if (features.isEmpty() || file == null || !settings.fileSupported(file)) null
            else file.presentableUrl to features
        } ?: return
        val (fileName, features) = snapshot
        when (val outcome = runBiomeSaveOperation { execute(project, document, features) }) {
            BiomeSaveOutcome.Completed -> Unit
            BiomeSaveOutcome.TimedOut -> LOG.warn(BiomeBundle.message("biome.save.timeout", fileName))
            is BiomeSaveOutcome.Failed -> {
                val description = BiomeBundle.message("biome.save.failure", fileName)
                LOG.warn(description, outcome.cause)
                NotificationGroupManager.getInstance().getNotificationGroup("Biome")
                    .createNotification(description, outcome.cause.message.orEmpty(), NotificationType.ERROR)
                    .notify(project)
            }
        }
    }

    companion object {
        private val LOG = logger<BiomeCheckOnSaveAction>()
    }
}
