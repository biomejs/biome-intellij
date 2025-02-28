package com.github.biomejs.intellijbiome.listeners

import com.github.biomejs.intellijbiome.extensions.isBiomeConfigFile
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class BiomeConfigListener(val project: Project) : BulkFileListener {
    override fun after(events: MutableList<out VFileEvent>) {
        super.after(events)
        events.forEach {
            if (it.file?.isBiomeConfigFile() == true) {
                val biomeServerService = project.service<BiomeServerService>()
                biomeServerService.restartBiomeServer()
            }
        }
    }
}
