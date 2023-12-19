package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomeBundle
import com.intellij.ide.actionsOnSave.ActionOnSaveBackedByOwnConfigurable
import com.intellij.ide.actionsOnSave.ActionOnSaveContext

class BiomeOnSaveApplySafeFixesActionInfo(actionOnSaveContext: ActionOnSaveContext) :
    ActionOnSaveBackedByOwnConfigurable<BiomeConfigurable>(
        actionOnSaveContext,
        BiomeConfigurable.CONFIGURABLE_ID,
        BiomeConfigurable::class.java
    ) {

    override fun getActionOnSaveName() =
        BiomeBundle.message("biome.run.safe.fixes.on.save.checkbox.on.actions.on.save.page")

    override fun isActionOnSaveEnabledAccordingToStoredState() = BiomeSettings.getInstance(project).applySafeFixesOnSave

    override fun isActionOnSaveEnabledAccordingToUiState(configurable: BiomeConfigurable) =
        configurable.runSafeFixesOnSaveCheckBox.isSelected

    override fun setActionOnSaveEnabled(configurable: BiomeConfigurable, enabled: Boolean) {
        configurable.runSafeFixesOnSaveCheckBox.isSelected = enabled
    }

    override fun getActionLinks() = listOf(createGoToPageInSettingsLink(BiomeConfigurable.CONFIGURABLE_ID))
}
