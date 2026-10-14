package com.github.biomejs.intellijbiome.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.io.path.ExperimentalPathApi

@OptIn(ExperimentalPathApi::class)
class BiomeSettingsTest : BasePlatformTestCase() {
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
