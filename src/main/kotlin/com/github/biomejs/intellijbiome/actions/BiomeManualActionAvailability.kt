package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager

internal fun isBiomeManualActionAvailable(event: AnActionEvent): Boolean {
    val project = event.project ?: return false
    if (project.isDisposed) return false
    val editor = event.getData(CommonDataKeys.EDITOR) ?: return false
    if (editor.isDisposed || editor.isViewer || !editor.document.isWritable) return false
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
    if (!file.isValid || file.isDirectory) return false
    return BiomeSettings.getInstance(project).let { it.isEnabled() && it.fileSupported(file) }
}
