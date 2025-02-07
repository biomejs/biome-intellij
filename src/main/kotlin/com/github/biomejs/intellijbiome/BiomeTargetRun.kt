package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.ExecutionException
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.lang.javascript.JavaScriptBundle
import com.intellij.openapi.project.Project

class BiomeTargetRunBuilder(val project: Project) {
    fun getBuilder(
        executable: String,
    ): ProcessCommandBuilder {
        if (executable.isEmpty()) {
            throw ExecutionException(BiomeBundle.message("biome.language.server.not.found"))
        }

        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode

        val builder: ProcessCommandBuilder = if (configurationMode == ConfigurationMode.MANUAL) {
            GeneralProcessCommandBuilder()
        } else {
            val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
            if (interpreter !is NodeJsLocalInterpreter && interpreter !is WslNodeInterpreter) {
                throw ExecutionException(JavaScriptBundle.message("lsp.interpreter.error"))
            }
            NodeProcessCommandBuilder(project, interpreter)
        }

        return builder.setExecutable(executable).setWorkingDirectory(project.basePath).setCharset(Charsets.UTF_8)
    }
}
