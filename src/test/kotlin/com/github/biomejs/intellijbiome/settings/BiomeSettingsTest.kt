package com.github.biomejs.intellijbiome.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.io.path.ExperimentalPathApi

@OptIn(ExperimentalPathApi::class)
class BiomeSettingsTest : BasePlatformTestCase() {
    fun testTypingPreferenceIsPersistentAndIndependentOfManualAndSaveFormatting() {
        val settings = BiomeSettings.getInstance(project)
        settings.loadState(BiomeSettingsState())
        settings.formatOnType = true
        settings.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(
            com.intellij.util.xmlb.XmlSerializer.serialize(settings.state), BiomeSettingsState::class.java))
        assertTrue(settings.formatOnType)
        assertFalse(settings.enableLspFormat)
        assertTrue(settings.getEnabledFeatures().isEmpty())
        settings.formatOnType = false
        settings.enableLspFormat = true
        settings.formatOnSave = true
        settings.sortImportOnSave = true
        settings.applySafeFixesOnSave = true
        assertFalse(settings.formatOnType)
    }

    fun testFormattingWhileTypingIsExplicitlyOffByDefault() {
        BiomeSettings.getInstance(project).loadState(BiomeSettingsState())
        val configurable = BiomeConfigurable(project)
        fun boxes(component: java.awt.Component): List<javax.swing.JCheckBox> =
            (if (component is javax.swing.JCheckBox) listOf(component) else emptyList()) +
                (if (component is java.awt.Container) component.components.flatMap(::boxes) else emptyList())
        try {
            val component = configurable.createComponent()!!
            configurable.reset()
            val checkbox = boxes(component).singleOrNull { it.text == "Format while typing with Biome" }
            assertNotNull("On-type formatting must have its own opt-in setting", checkbox)
            assertFalse(checkbox!!.isSelected)
        } finally { configurable.disposeUIResources() }
    }

    fun testConfigPathKeepsConfigurationFile() {
        val dir = createTempDirectory("biome-settings")
        try {
            for (name in listOf("biome.json", "biome.jsonc")) {
                val configFile = dir.resolve(name)
                configFile.writeText("{}")

                val settings = BiomeSettings.getInstance(project)
                settings.configPath = configFile.toString()

                assertEquals(configFile.toString(), settings.configPath)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
