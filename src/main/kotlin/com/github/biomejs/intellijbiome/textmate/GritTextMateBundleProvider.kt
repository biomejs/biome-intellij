package com.github.biomejs.intellijbiome.textmate

import com.intellij.openapi.application.PluginPathManager
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider

class GritTextMateBundleProvider : TextMateBundleProvider {
    override fun getBundles(): List<TextMateBundleProvider.PluginBundle> =
        PluginPathManager.getPluginResource(javaClass, "grit-vscode/extension")
            ?.let { listOf(TextMateBundleProvider.PluginBundle("grit", it.toPath())) }
            ?: emptyList()
}
