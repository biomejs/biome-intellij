package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware


class ReformatWithBiomeAction : AnAction(), DumbAware {
    override fun actionPerformed(actionEvent: AnActionEvent) {
        val project = actionEvent.project
        if (project == null || project.isDefault) return

        val editor: Editor? = actionEvent.getData(CommonDataKeys.EDITOR)

        if (editor != null) {
            val documentManager = FileDocumentManager.getInstance()
            // We should save document before running Biome, because Biome will read the file from disk and user changes can be lost
            if (documentManager.isDocumentUnsaved(editor.document)) {
                documentManager.saveDocument(editor.document)
            }
            val settings = BiomeSettings.getInstance(project)
            BiomeCheckRunner().run(project, settings.getEnabledFeatures(), arrayOf(editor.document))
        }
    }

    override fun update(actionEvent: AnActionEvent) {
        val project = actionEvent.project
        if (project == null || project.isDefault) {
            actionEvent.presentation.isEnabledAndVisible = false
            return
        }

        val settings = BiomeSettings.getInstance(project)
        actionEvent.presentation.isEnabledAndVisible = settings.isEnabled()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}
