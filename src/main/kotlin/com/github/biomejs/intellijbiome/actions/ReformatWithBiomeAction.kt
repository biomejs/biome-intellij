package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.Feature
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import java.util.*


class ReformatWithBiomeAction : AnAction(), DumbAware {
    override fun actionPerformed(actionEvent: AnActionEvent) {
        val project = actionEvent.project
        if (project == null || project.isDefault) return

        val editor: Editor? = actionEvent.getData(CommonDataKeys.EDITOR)

        if (editor != null) {
            BiomeCheckRunner().run(project, EnumSet.of(Feature.Format), arrayOf(editor.document))
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
