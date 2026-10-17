package com.github.biomejs.intellijbiome.codestyle

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class BiomeImportCodeStyleAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = availableFile(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = availableFile(event) ?: return
        val language = BiomeCodeStyleLanguage.forFile(file) ?: return
        val path = file.path
        runWithModalProgressBlocking(project, BiomeBundle.message("biome.import.code.style.progress")) {
            val group = NotificationGroupManager.getInstance().getNotificationGroup("Biome")
            fun failure(message: String) {
                if (!project.isDisposed) group.createNotification(
                    BiomeBundle.message("biome.import.code.style.failure.title"),
                    BiomeBundle.message("biome.import.code.style.failure.description", StringUtil.escapeXmlEntities(message)),
                    NotificationType.WARNING,
                ).notify(project)
            }
            try {
                val preferences = readBiomeCodeStyle(project, file)
                withContext(Dispatchers.EDT) {
                    currentCoroutineContext().ensureActive()
                    if (project.isDisposed || !file.isValid || file.path != path ||
                        !BiomeSettings.getInstance(project).isEnabled()) return@withContext
                    applyBiomeCodeStyle(project, language, preferences)
                    group.createNotification(
                        BiomeBundle.message("biome.import.code.style.success.title"),
                        BiomeBundle.message("biome.import.code.style.success.description",
                            language.displayName, StringUtil.escapeXmlEntities(file.name)),
                        NotificationType.INFORMATION,
                    ).notify(project)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (problem: Exception) {
                if (problem is ControlFlowException) throw problem
                failure(problem.message ?: problem.toString())
            }
        }
    }

    private fun availableFile(event: AnActionEvent): VirtualFile? {
        val project = event.project?.takeUnless { it.isDisposed || it.isDefault } ?: return null
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: event.getData(CommonDataKeys.EDITOR)?.takeUnless { it.isDisposed }?.document
                ?.let { FileDocumentManager.getInstance().getFile(it) }
            ?: return null
        if (!file.isValid || file.isDirectory || !file.isInLocalFileSystem || BiomeCodeStyleLanguage.forFile(file) == null) return null
        val settings = BiomeSettings.getInstance(project)
        return file.takeIf { settings.isEnabled() && settings.fileSupported(it) }
    }
}
