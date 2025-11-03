package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomeBundle
import com.intellij.ide.actionsOnSave.ActionOnSaveBackedByOwnConfigurable
import com.intellij.ide.actionsOnSave.ActionOnSaveComment
import com.intellij.ide.actionsOnSave.ActionOnSaveContext

class BiomeOnSaveFormatActionInfo(actionOnSaveContext: ActionOnSaveContext) :
    ActionOnSaveBackedByOwnConfigurable<BiomeConfigurable>(
        actionOnSaveContext,
        BiomeConfigurable.CONFIGURABLE_ID,
        BiomeConfigurable::class.java
    ) {

    override fun getActionOnSaveName() =
        BiomeBundle.message("biome.format.on.save.checkbox.on.actions.on.save.page")

    override fun isApplicableAccordingToStoredState(): Boolean =
        BiomeSettings.getInstance(project).configurationMode != ConfigurationMode.DISABLED

    override fun isActionOnSaveEnabledAccordingToStoredState() = BiomeSettings.getInstance(project).formatOnSave

    override fun isApplicableAccordingToUiState(configurable: BiomeConfigurable): Boolean =
        !configurable.disabledConfiguration.isSelected

    override fun isActionOnSaveEnabledAccordingToUiState(configurable: BiomeConfigurable) =
        configurable.runFormatOnSaveCheckBox.isSelected

    override fun setActionOnSaveEnabled(configurable: BiomeConfigurable,
        enabled: Boolean) {
        configurable.runFormatOnSaveCheckBox.isSelected = enabled
    }

    override fun getActionLinks() = listOf(createGoToPageInSettingsLink(BiomeConfigurable.CONFIGURABLE_ID))

    override fun getCommentAccordingToStoredState(): ActionOnSaveComment? {
        if (BiomeSettings.getInstance(project).configurationMode == ConfigurationMode.DISABLED) {
            return ActionInfo.disabled()
        }

        return null
    }

    override fun getCommentAccordingToUiState(configurable: BiomeConfigurable): ActionOnSaveComment? {
        if (configurable.disabledConfiguration.isSelected) {
            return ActionInfo.disabled()
        }

        return null
    }
}
