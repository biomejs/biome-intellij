package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class RestartBiomeServerAction : AnAction() {
    override fun actionPerformed(actionEvent: AnActionEvent) {
        val project = actionEvent.project
        if (project == null || project.isDefault) return

        val biomeServerService = project.service<BiomeServerService>()

        biomeServerService.restartBiomeServer()
        biomeServerService.notifyRestart()
    }
}
