package com.github.biomejs.intellijbiome.settings

import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BiomeConfigurableDefaultProjectTest : BasePlatformTestCase() {

    // Settings opened from the Welcome screen or "Settings for New Projects" target the default project,
    // which has no LSP servers. Applying them must not try to restart the Biome server.
    fun testApplyOnDefaultProjectDoesNotRestartServer() {
        val configurable = BiomeConfigurable(ProjectManager.getInstance().defaultProject)
        try {
            configurable.createComponent()
            configurable.apply()
        } finally {
            configurable.disposeUIResources()
        }
    }
}
