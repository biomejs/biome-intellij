package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomeBundle
import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider

class BiomeOnSaveInfoProvider : ActionOnSaveInfoProvider() {
    override fun getActionOnSaveInfos(context: ActionOnSaveContext):
        List<ActionOnSaveInfo> = listOf(
        BiomeOnSaveFormatActionInfo(context),
        BiomeOnSaveApplySafeFixesActionInfo(context),
        BiomeOnSaveApplyUnsafeFixesActionInfo(context)
    )

    override fun getSearchableOptions(): Collection<String> {
        return listOf(
            BiomeBundle.message("biome.run.format.on.save.checkbox.on.actions.on.save.page"),
            BiomeBundle.message("biome.run.safe.fixes.on.save.checkbox.on.actions.on.save.page"),
            BiomeBundle.message("biome.run.unsafe.fixes.on.save.checkbox.on.actions.on.save.page")
        )
    }
}

