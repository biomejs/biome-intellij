package com.github.biomejs.intellijbiome.listeners

import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class BiomeConfigListener(val project: Project) : BulkFileListener {
    override fun after(events: MutableList<out VFileEvent>) {
        super.after(events)
        events.forEach {
            if (it.file?.name?.contains(BiomePackage.configName) == true && BiomePackage.configValidExtensions.contains(
                    it.file?.extension)) {
                val biomeServerService = project.service<BiomeServerService>()
                biomeServerService.restartBiomeServer()
            }
        }
    }
}
