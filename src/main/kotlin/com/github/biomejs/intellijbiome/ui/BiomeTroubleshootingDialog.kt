package com.github.biomejs.intellijbiome.ui

import com.github.biomejs.intellijbiome.BiomeBundle
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import java.awt.event.ActionEvent
import java.awt.datatransfer.StringSelection
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JTextArea

internal class BiomeTroubleshootingDialog(project: Project, text: String) : DialogWrapper(project) {
    internal val preview = JTextArea(text, 24, 100).apply {
        isEditable = false
        caretPosition = 0
        accessibleContext.accessibleName = BiomeBundle.message("biome.troubleshooting.title")
    }
    internal val copyAction: Action = object : AbstractAction(BiomeBundle.message("biome.troubleshooting.copy")) {
        override fun actionPerformed(event: ActionEvent) {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }
    }
    init {
        title = BiomeBundle.message("biome.troubleshooting.title")
        setCancelButtonText(BiomeBundle.message("biome.troubleshooting.close"))
        init()
    }
    override fun createCenterPanel(): JComponent = JBScrollPane(preview)
    override fun createActions(): Array<Action> = arrayOf(copyAction, cancelAction)
    override fun getPreferredFocusedComponent(): JComponent = preview
}
