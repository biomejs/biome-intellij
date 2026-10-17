package com.github.biomejs.intellijbiome.lsp

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** These controls fail if a newer-runtime lane silently falls back to the compile SDK. */
class BiomeIdeRuntimeTest : BasePlatformTestCase() {
    override fun setUp() {
        val expected = System.getProperty("biome.test.expected.kotlin")
        assertNotNull("The runtime lane must declare its Kotlin version", expected)
        val kotlinVersion = KotlinVersion.CURRENT.toString()
        println("BIOME_KOTLIN_RUNTIME_VERSION=$kotlinVersion")
        assertEquals(expected, kotlinVersion)
        super.setUp()
    }
    fun testPinnedIdeRuntimeIsActuallyLoaded() {
        val expected = System.getProperty("biome.test.expected.build")
        assertNotNull("The runtime lane must declare its exact IDE build", expected)
        assertEquals(expected, ApplicationInfo.getInstance().build.asString())
        println("BIOME_RUNTIME_IDENTITY=${ApplicationInfo.getInstance().build.asString()}; java=${System.getProperty("java.runtime.version")}")
    }
}
