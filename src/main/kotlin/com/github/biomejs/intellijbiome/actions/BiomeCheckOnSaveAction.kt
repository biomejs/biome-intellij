package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project

class BiomeCheckOnSaveAction : ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {
    override fun isEnabledForProject(project: Project): Boolean {
        val settings = BiomeSettings.getInstance(project)

        return settings.applySafeFixesOnSave || settings.applyUnsafeFixesOnSave || settings.formatOnSave
    }

    override fun processDocuments(project: Project, documents: Array<Document>) {
        BiomeCheckRunner(project).run(documents)
    }
}
