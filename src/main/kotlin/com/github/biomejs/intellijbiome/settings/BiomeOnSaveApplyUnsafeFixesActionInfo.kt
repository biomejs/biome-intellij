package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomePackage
import com.intellij.ide.actionsOnSave.ActionOnSaveBackedByOwnConfigurable
import com.intellij.ide.actionsOnSave.ActionOnSaveComment
import com.intellij.ide.actionsOnSave.ActionOnSaveContext

class BiomeOnSaveApplyUnsafeFixesActionInfo(actionOnSaveContext: ActionOnSaveContext) :
    ActionOnSaveBackedByOwnConfigurable<BiomeConfigurable>(
        actionOnSaveContext,
        BiomeConfigurable.CONFIGURABLE_ID,
        BiomeConfigurable::class.java
    ) {

    override fun getActionOnSaveName() =
        BiomeBundle.message("biome.run.unsafe.fixes.on.save.checkbox.on.actions.on.save.page")

    override fun isActionOnSaveEnabledAccordingToStoredState() =
        BiomeSettings.getInstance(project).applyUnsafeFixesOnSave

    override fun isActionOnSaveEnabledAccordingToUiState(configurable: BiomeConfigurable) =
        configurable.runUnsafeFixesOnSaveCheckBox.isSelected

    override fun setActionOnSaveEnabled(configurable: BiomeConfigurable, enabled: Boolean) {
        configurable.runUnsafeFixesOnSaveCheckBox.isSelected = enabled
    }

    override fun getCommentAccordingToUiState(configurable: BiomeConfigurable): ActionOnSaveComment? {
        val biomePackage = BiomePackage(project)
        val version = biomePackage.versionNumber()
        return ActionInfo.defaultComment(version, configurable.runForFilesField.text.trim(), isActionOnSaveEnabled)
    }

    override fun getCommentAccordingToStoredState(): ActionOnSaveComment? {
        val biomePackage = BiomePackage(project)
        val settings = BiomeSettings.getInstance(project)
        val version = biomePackage.versionNumber()

        return ActionInfo.defaultComment(version, settings.filePattern, isActionOnSaveEnabled)
    }

    override fun getActionLinks() = listOf(createGoToPageInSettingsLink(BiomeConfigurable.CONFIGURABLE_ID))
}
