package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.services.BiomeTroubleshootingService
import com.github.biomejs.intellijbiome.ui.BiomeTroubleshootingDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.runWithModalProgressBlocking

class BiomeCollectTroubleshootingAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.let { !it.isDisposed && !it.isDefault } == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project?.takeUnless { it.isDisposed || it.isDefault } ?: return
        val service = project.service<BiomeTroubleshootingService>()
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: event.getData(CommonDataKeys.EDITOR)?.takeUnless { it.isDisposed }?.document
                ?.let { FileDocumentManager.getInstance().getFile(it) }
        val targets = ReadAction.compute<List<BiomeTroubleshootingService.Target>, RuntimeException> { service.targets(file) }
        val target = when (targets.size) {
            0 -> null
            1 -> targets.single()
            else -> {
                val names = targets.map { it.descriptor.roots.joinToString { root -> root.presentableUrl } }.toTypedArray()
                val chosen = Messages.showChooseDialog(project,
                    BiomeBundle.message("biome.troubleshooting.choose"), BiomeBundle.message("biome.troubleshooting.title"),
                    Messages.getQuestionIcon(), names, names.first())
                targets.getOrNull(chosen) ?: return
            }
        }
        val report = if (target == null) {
            ReadAction.compute<BiomeTroubleshootingService.Report, RuntimeException> { service.localReport(file) }
        } else runWithModalProgressBlocking(project, BiomeBundle.message("biome.troubleshooting.progress")) {
            service.collect(target)
        } ?: return
        service.deliver(report) { BiomeTroubleshootingDialog(project, it).show() }
    }
}
