package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

internal data class BiomeManualActionTarget(val file: VirtualFile, val editor: Editor?)

internal fun biomeManualActionTarget(event: AnActionEvent): BiomeManualActionTarget? {
    val project = event.project ?: return null
    if (project.isDisposed) return null
    val manager = FileDocumentManager.getInstance()
    val selected = event.getData(CommonDataKeys.VIRTUAL_FILE)
    val editor = event.getData(CommonDataKeys.EDITOR)?.takeIf {
        selected == null || manager.getFile(it.document) == selected
    }
    if (editor != null && (editor.isDisposed || editor.isViewer || !editor.document.isWritable)) return null
    val file = selected ?: editor?.let { manager.getFile(it.document) } ?: return null
    if (!file.isValid || file.isDirectory || (editor == null && !file.isWritable)) return null
    if (manager.getCachedDocument(file)?.isWritable == false) return null
    return BiomeManualActionTarget(file, editor)
}

internal fun isBiomeManualActionAvailable(event: AnActionEvent): Boolean {
    val project = event.project ?: return false
    val target = biomeManualActionTarget(event) ?: return false
    return BiomeSettings.getInstance(project).let { it.isEnabled() && it.fileSupported(target.file) }
}
