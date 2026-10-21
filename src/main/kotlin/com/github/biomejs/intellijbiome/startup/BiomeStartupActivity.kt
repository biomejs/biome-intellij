package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.lsp.api.LspServerManager

/** Retries discovery for restored editors after project opening. */
class BiomeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (project.isDisposed || project.isDefault || !BiomeSettings.getInstance(project).isEnabled()) return
        LspServerManager.getInstance(project).startServersIfNeeded(BiomeLspServerSupportProvider::class.java)
    }
}
