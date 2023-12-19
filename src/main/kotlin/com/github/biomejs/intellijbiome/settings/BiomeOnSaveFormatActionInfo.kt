package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomeBundle
import com.intellij.ide.actionsOnSave.ActionOnSaveBackedByOwnConfigurable
import com.intellij.ide.actionsOnSave.ActionOnSaveContext

class BiomeOnSaveFormatActionInfo(actionOnSaveContext: ActionOnSaveContext) :
    ActionOnSaveBackedByOwnConfigurable<BiomeConfigurable>(
        actionOnSaveContext,
        BiomeConfigurable.CONFIGURABLE_ID,
        BiomeConfigurable::class.java
    ) {

    override fun getActionOnSaveName() =
        BiomeBundle.message("biome.run.format.on.save.checkbox.on.actions.on.save.page")

    override fun isActionOnSaveEnabledAccordingToStoredState() = BiomeSettings.getInstance(project).formatOnSave

    override fun isActionOnSaveEnabledAccordingToUiState(configurable: BiomeConfigurable) =
        configurable.runFormatOnSaveCheckBox.isSelected

    override fun setActionOnSaveEnabled(configurable: BiomeConfigurable, enabled: Boolean) {
        configurable.runFormatOnSaveCheckBox.isSelected = enabled
    }

    override fun getActionLinks() = listOf(createGoToPageInSettingsLink(BiomeConfigurable.CONFIGURABLE_ID))
}
