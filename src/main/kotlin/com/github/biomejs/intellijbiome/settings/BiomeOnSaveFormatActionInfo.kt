package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomePackage
import com.intellij.ide.actionsOnSave.ActionOnSaveBackedByOwnConfigurable
import com.intellij.ide.actionsOnSave.ActionOnSaveComment
import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.openapi.util.text.StringUtil

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
}

class ActionInfo {
    companion object {
        fun defaultComment(
            version: String?,
            filePattern: String,
            isActionOnSaveEnabled: Boolean
        ): ActionOnSaveComment? {
            if (version == null) {
                val message = BiomeBundle.message("biome.run.on.save.package.not.specified.warning")
                return if (isActionOnSaveEnabled) ActionOnSaveComment.warning(message) else ActionOnSaveComment.info(
                    message
                )
            }

            return ActionOnSaveComment.info(
                BiomeBundle.message(
                    "biome.run.on.save.version.and.files.pattern",
                    shorten(version, 15),
                    shorten(filePattern, 40)
                )
            )
        }

        fun shorten(s: String, max: Int) = StringUtil.shortenTextWithEllipsis(s, max, 0, true)
    }
}
