package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomePackage
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

class BiomeBlankConfigPathTest : BasePlatformTestCase() {
    fun testBlankValuesUseEmptyOverride() {
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.MANUAL
        for (blank in listOf("", " ", "\t\n", " \t\r\n ", "\u2003")) {
            settings.configPath = blank
            assertEquals("", settings.configPath)
            assertTrue(settings.state.configPath.isNullOrEmpty())
            assertEquals("", BiomePackage(project).configPath())
            assertEquals("", reload(settings.state).configPath)
        }
    }

    fun testPersistedWhitespaceUsesEmptyOverride() {
        val settings = BiomeSettings.getInstance(project)
        for (blank in listOf(" ", "\t\n", " \t\r\n ", "\u2003")) {
            val state = BiomeSettingsState().apply {
                configurationMode = ConfigurationMode.MANUAL
                configPath = blank
            }
            settings.loadState(reload(state).state)
            assertEquals(blank, settings.state.configPath)
            assertEquals("", settings.configPath)
            assertEquals("", BiomePackage(project).configPath())
        }
    }

    fun testAbsentPersistedOverrideRemainsEmpty() {
        val settings = BiomeSettings.getInstance(project)
        settings.loadState(reload(BiomeSettingsState().apply {
            configurationMode = ConfigurationMode.MANUAL
        }).state)
        assertEquals("", settings.configPath)
        assertEquals("", BiomePackage(project).configPath())
    }

    fun testNonblankPersistedPathsAreUnchanged() {
        for (path in nonblankPaths()) {
            val loaded = reload(BiomeSettingsState().apply { configPath = path })
            assertEquals(path, loaded.state.configPath)
            assertEquals(path, loaded.configPath)
        }
    }

    fun testNonblankProgrammaticPathsKeepEveryCharacterWhenPersisted() {
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.MANUAL
        for (path in nonblankPaths()) {
            settings.configPath = path
            assertEquals(path, settings.state.configPath)
            assertEquals(path, settings.configPath)
            assertEquals(path, BiomePackage(project).configPath())
            val loaded = reload(settings.state)
            assertEquals(path, loaded.state.configPath)
            assertEquals(path, loaded.configPath)
        }
    }

    private fun nonblankPaths() = listOf(
        "/space dir/biome.jsonc", "/space dir", "  /space dir/biome.json  ",
        "./relative path/custom.jsonc", "../config.json", "\tconfig/custom.jsonc\r\n", "é/config name.jsonc",
    )

    private fun reload(state: BiomeSettingsState): BiomeSettings = BiomeSettings().apply {
        loadState(XmlSerializer.deserialize(XmlSerializer.serialize(state), BiomeSettingsState::class.java))
    }
}
