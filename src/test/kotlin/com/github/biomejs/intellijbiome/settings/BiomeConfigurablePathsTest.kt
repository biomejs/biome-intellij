package com.github.biomejs.intellijbiome.settings

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class BiomeConfigurablePathsTest : BasePlatformTestCase() {
    fun testManualPathsApplyAndResetIndependently() {
        val initialConfig = myFixture.addFileToProject("initial project/biome.json", "{}").virtualFile.path
        val changedConfig = myFixture.addFileToProject("changed project/biome.jsonc", "{}").virtualFile.path
        val initialExecutable = "/tools/initial biome"
        val changedExecutable = "/tools/changed biome"
        val defaultProject = ProjectManager.getInstance().defaultProject
        val settings = BiomeSettings.getInstance(defaultProject)
        val previousState = settings.state
        settings.loadState(BiomeSettingsState().apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = initialExecutable
            configPath = initialConfig
        })
        val configurable = BiomeConfigurable(defaultProject)
        try {
            val panel = checkNotNull(configurable.createComponent())
            configurable.reset()
            val fields = UIUtil.findComponentsOfType(panel, TextFieldWithBrowseButton::class.java)
            val executable = fields.single { it.text == initialExecutable }
            val configuration = fields.single { it.text == initialConfig }

            executable.text = changedExecutable
            configuration.text = changedConfig
            assertTrue(configurable.isModified())
            assertEquals(initialExecutable, settings.executablePath)
            assertEquals(initialConfig, settings.configPath)

            configurable.reset()
            assertEquals(initialExecutable, executable.text)
            assertEquals(initialConfig, configuration.text)
            assertFalse(configurable.isModified())

            executable.text = changedExecutable
            configuration.text = changedConfig
            configurable.apply()
            assertEquals(changedExecutable, settings.executablePath)
            assertEquals(changedConfig, settings.configPath)
        } finally {
            configurable.disposeUIResources()
            settings.loadState(previousState)
        }
    }
}
