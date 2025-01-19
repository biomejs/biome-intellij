package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import kotlinx.coroutines.withTimeout

class BiomeCheckOnSaveAction : ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {
    override fun isEnabledForProject(project: Project): Boolean {
        return !BiomeSettings.getInstance(project).getEnabledFeatures().isEmpty()
    }

    override fun processDocuments(project: Project,
        documents: Array<Document>) {
        val features = BiomeSettings.getInstance(project).getEnabledFeatures()
        val featuresInfo = features.joinToString(prefix = "(", postfix = ")") { it.toString().lowercase() }
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Biome")

        runWithModalProgressBlocking(project,
            BiomeBundle.message("biome.run.biome.check.with.features", featuresInfo)) {
            try {
                withTimeout(5_000) {
                    documents.filter {
                        val settings = BiomeSettings.getInstance(project)
                        val manager = FileDocumentManager.getInstance()
                        val virtualFile = manager.getFile(it) ?: return@filter false
                        return@filter settings.fileSupported(virtualFile)
                    }.forEach {
                        BiomeServerService.getInstance(project).executeFeatures(it, features)
                    }
                }
            } catch (e: Exception) {
                notificationGroup.createNotification(
                    title = BiomeBundle.message("biome.apply.feature.on.save.failure.label", featuresInfo),
                    content = BiomeBundle.message(
                        "biome.apply.feature.on.save.failure.description",
                        featuresInfo,
                        e.message.toString()
                    ),
                    type = NotificationType.ERROR).notify(project)
            }
        }
    }
}
