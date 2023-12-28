package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomeStdinRunner
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project


class ApplyUnsafeFixesOnSaveAction : ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {
    override fun isEnabledForProject(project: Project): Boolean =
        BiomeSettings.getInstance(project).applyUnsafeFixesOnSave

    override fun processDocuments(project: Project, documents: Array<Document?>) {
        val runner = BiomeStdinRunner(project)

        OnSaveHelper().formatDocuments(
            project,
            documents.filterNotNull().toList(),
            BiomeBundle.message("biome.apply.unsafe.fix.with.biome")
        ) { request -> runner.applyUnsafeFixes(request) }
    }

}
