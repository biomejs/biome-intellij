package com.github.biomejs.intellijbiome

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BiomeBundleTest : BasePlatformTestCase() {
    fun testMessagesUsePluginResourcesAndFormatArguments() {
        assertEquals("Biome", BiomeBundle.message("name"))
        assertEquals("An error occurred while executing features Format: server unavailable",
            BiomeBundle.message("biome.apply.feature.on.save.failure.description", "Format", "server unavailable"))
    }

    fun testMessagePointerResolvesArgumentsWhenRead() {
        var fileName = "first.js"
        val file = object {
            override fun toString(): String = fileName
        }
        val message = BiomeBundle.messagePointer("biome.manual.unchanged.description", file)
        fileName = "second.js"
        assertEquals("Biome found no changes to apply to second.js.", message.get())
    }
}
