package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.configuration.BiomeConfigurationResolution
import com.github.biomejs.intellijbiome.configuration.BiomeConfigurationResolver
import com.github.biomejs.intellijbiome.configuration.BiomeConfigurationSettings
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile

class BiomeOpenConfigurationAction(private val contextFile: VirtualFile? = null) : DumbAwareAction(
    BiomeBundle.message("action.BiomeOpenConfiguration.text"),
    BiomeBundle.message("action.BiomeOpenConfiguration.description"),
    AllIcons.Actions.EditSource,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = available(event)
    }

    override fun actionPerformed(event: AnActionEvent) {
        if (!available(event)) return
        val project = event.project ?: return
        val result = BiomeConfigurationResolver.resolve(project, selectedFile(event), BiomeConfigurationSettings.capture(project))
        if (result is BiomeConfigurationResolution.Found) {
            if (result.file.isValid) FileEditorManager.getInstance(project).openFile(result.file, true)
            return
        }
        val message = when (result) {
            is BiomeConfigurationResolution.Ambiguous -> BiomeBundle.message("biome.configuration.open.ambiguous")
            is BiomeConfigurationResolution.Unavailable -> when (result.reason) {
                BiomeConfigurationResolution.Reason.UNRESOLVED_TARGET -> BiomeBundle.message("biome.configuration.open.target.unavailable")
                BiomeConfigurationResolution.Reason.NO_PROJECT_BASE -> BiomeBundle.message("biome.configuration.open.no.project.base")
                else -> BiomeBundle.message("biome.configuration.open.missing")
            }
            is BiomeConfigurationResolution.Found -> return
        }
        NotificationGroupManager.getInstance().getNotificationGroup("Biome").createNotification(
            BiomeBundle.message("biome.configuration.open.unavailable.title"), message, NotificationType.INFORMATION,
        ).notify(project)
    }

    private fun available(event: AnActionEvent): Boolean {
        val project = event.project?.takeUnless { it.isDisposed || it.isDefault } ?: return false
        return BiomeSettings.getInstance(project).isEnabled() && selectedFile(event)?.isValid != false
    }

    private fun selectedFile(event: AnActionEvent): VirtualFile? = contextFile
        ?: event.getData(CommonDataKeys.VIRTUAL_FILE)
        ?: event.getData(CommonDataKeys.EDITOR)?.takeUnless { it.isDisposed }?.document
            ?.let { FileDocumentManager.getInstance().getFile(it) }
}
