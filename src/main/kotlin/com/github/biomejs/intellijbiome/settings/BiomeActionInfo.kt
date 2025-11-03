package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomeBundle
import com.intellij.ide.actionsOnSave.ActionOnSaveComment

class ActionInfo {
    companion object {
        fun disabled(): ActionOnSaveComment {
            return ActionOnSaveComment.info(
                BiomeBundle.message("biome.run.on.save.disabled")
            )
        }

        fun lspFormattingIsEnabled(): ActionOnSaveComment {
            return ActionOnSaveComment.info(
                BiomeBundle.message("biome.run.on.save.lspFormattingIsEnabled")
            )
        }
    }
}
