package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomeStdinRunner
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project


class ApplySafeFixesOnSaveAction : ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {
    override fun isEnabledForProject(project: Project): Boolean =
        BiomeSettings.getInstance(project).applySafeFixesOnSave

    override fun processDocuments(project: Project, documents: Array<Document?>) {
        val runner = BiomeStdinRunner.getInstance(project)

        OnSaveHelper().formatDocuments(
            project,
            documents.filterNotNull().toList(),
            BiomeBundle.message("biome.apply.safe.fix.with.biome")
        ) { request -> runner.applySafeFixes(request) }
    }
}
