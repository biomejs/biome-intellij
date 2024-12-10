package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomeBundle
import com.intellij.ide.actionsOnSave.ActionOnSaveComment
import com.intellij.openapi.util.text.StringUtil

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
