package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.Feature
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import java.util.*


class BiomeCheckOnSaveAction() :
    ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {
    private var features: EnumSet<Feature> = EnumSet.noneOf(Feature::class.java)
    override fun isEnabledForProject(project: Project): Boolean {
        val settings = BiomeSettings.getInstance(project)

        setFeatures(settings)

        return settings.formatOnSave || settings.applySafeFixesOnSave || settings.applyUnsafeFixesOnSave
    }

    override fun processDocuments(project: Project, documents: Array<Document>) {
        BiomeCheckRunner().run(project, features, documents)
    }

    private fun setFeatures(settings: BiomeSettings) {
        features = EnumSet.noneOf(Feature::class.java)

        if (settings.formatOnSave) {
            features.add(Feature.Format)
        }

        if (settings.applySafeFixesOnSave) {
            features.add(Feature.SafeFixes)
        }

        if (settings.applyUnsafeFixesOnSave) {
            features.add(Feature.UnsafeFixes)
        }
    }
}
