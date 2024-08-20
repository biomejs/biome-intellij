package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project


class BiomeCheckOnSaveAction :
    ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {
    override fun isEnabledForProject(project: Project): Boolean {
        val settings = BiomeSettings.getInstance(project)

        return settings.formatOnSave || settings.applySafeFixesOnSave || settings.applyUnsafeFixesOnSave
    }

    override fun processDocuments(project: Project, documents: Array<Document>) {
        val settings = BiomeSettings.getInstance(project)
        BiomeCheckRunner().run(project, settings.getEnabledFeatures(), documents)
    }
}
