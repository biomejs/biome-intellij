package com.github.biomejs.intellijbiome.widgets

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.listeners.BIOME_CONFIG_RESOLVED_TOPIC
import com.github.biomejs.intellijbiome.listeners.BiomeConfigResolvedListener
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget.WidgetPresentation
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.impl.LspServerImpl

class BiomeWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget,
    StatusBarWidget.MultipleTextValuesPresentation {
    private val logger: Logger = Logger.getInstance(javaClass)
    private val biomePackage = BiomePackage(project)

    init {
        project
            .messageBus
            .connect(this)
            .subscribe(BIOME_CONFIG_RESOLVED_TOPIC, object : BiomeConfigResolvedListener {
                override fun resolved(version: String) {
                    update()
                }
            })
    }

    override fun ID(): String {
        return javaClass.name
    }

    override fun getPresentation(): WidgetPresentation {
        return this
    }

    override fun getSelectedValue(): String? {
        val settings = BiomeSettings.getInstance(project)
        if (!settings.isEnabled()) {
            return null
        }

        val progressManager = ProgressManager.getInstance()
        val version = progressManager.runProcessWithProgressSynchronously<String, Exception>({
            biomePackage.versionNumber()
        }, BiomeBundle.message("biome.loading"), true, project)

        if (version.isNullOrEmpty()) {
            return null
        }

        return BiomeBundle.message("biome.widget.version", version)
    }

    override fun getTooltipText(): String {
        val lspServerManager = LspServerManager.getInstance(project)
        val lspServer = lspServerManager.getServersForProvider(BiomeLspServerSupportProvider::class.java).firstOrNull()

        return when (lspServer) {
            is LspServerImpl -> {
                if (lspServer.isRunning) {
                    BiomeBundle.message("biome.language.server.is.running")
                } else {
                    BiomeBundle.message("biome.language.server.is.stopped")
                }
            }

            else -> {
                BiomeBundle.message("biome.language.server.is.stopped")
            }
        }
    }

    private fun update() {
        if (myStatusBar == null) {
            logger.warn("Failed to update biome statusbar")
            return
        }

        myStatusBar!!.updateWidget(ID())
    }

}
