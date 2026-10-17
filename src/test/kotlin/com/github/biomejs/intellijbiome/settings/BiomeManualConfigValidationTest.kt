package com.github.biomejs.intellijbiome.settings

import com.github.biomejs.intellijbiome.BiomePackage
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertThrows
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JRadioButton

class BiomeManualConfigValidationTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private val settings get() = BiomeSettings.getInstance(project)

    fun testInvalidManualPathsCannotApply() {
        val valid = myFixture.tempDirFixture.createFile("valid/biome.json", "{}").parent.path
        val empty = myFixture.tempDirFixture.findOrCreateDir("empty").path
        val other = myFixture.tempDirFixture.createFile("other.txt", "{}").path
        val namedDirectory = myFixture.tempDirFixture.findOrCreateDir("named/biome.json").path
        val invalidChild = myFixture.tempDirFixture.findOrCreateDir("invalid-child/biome.jsonc").parent.path
        for (path in listOf("$valid/missing", empty, other, namedDirectory, invalidChild, "bad\u0000path")) {
            settings.configurationMode = ConfigurationMode.MANUAL
            settings.configPath = valid
            panel { configurable, component ->
                configField(component).text = path
                assertThrows("Invalid Manual path must not apply: $path", ConfigurationException::class.java) {
                    configurable.apply()
                }
                assertEquals(valid, settings.configPath)
            }
        }
    }

    fun testSupportedFilesAndDirectoriesCanApply() {
        val json = myFixture.tempDirFixture.createFile("both/biome.json", "{}").path
        val jsonc = myFixture.tempDirFixture.createFile("both/biome.jsonc", "{}").path
        val jsonOnly = myFixture.tempDirFixture.createFile("json-only/biome.json", "{}").parent.path
        val jsoncOnly = myFixture.tempDirFixture.createFile("jsonc-only/biome.jsonc", "{}").parent.path
        val malformed = myFixture.tempDirFixture.createFile("malformed/biome.json", "{broken").path
        val namedDirectory = myFixture.tempDirFixture.createFile("named/biome.json/biome.jsonc", "{}").parent.path
        val spaces = myFixture.tempDirFixture.createFile("space dir/biome.jsonc", "{}").path
        for (path in listOf(json, jsonc, jsonOnly, jsoncOnly, malformed, namedDirectory, spaces)) {
            settings.configurationMode = ConfigurationMode.MANUAL
            panel { configurable, component ->
                configField(component).text = path
                configurable.apply()
                assertEquals(ConfigurationMode.MANUAL, settings.configurationMode)
                assertEquals(path, settings.configPath)
            }
        }
    }

    fun testCustomJsonAndJsoncFilesCanApply() {
        for (name in listOf("custom.json", "custom.jsonc")) {
            val path = myFixture.tempDirFixture.createFile("custom names/$name", "{}").path
            assertCanApply(path)
        }
    }

    fun testNonblankInputIsNotTrimmedBeforeValidation() {
        val selected = myFixture.tempDirFixture.createFile("selected/custom.jsonc", "{}").path
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.configPath = selected
        panel { configurable, component ->
            configField(component).text = " $selected "
            assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertEquals(selected, settings.configPath)
            assertEquals(selected, settings.state.configPath)
        }
    }

    fun testRelativeFilesAndDirectoriesResolveAgainstProjectRoot() {
        val file = myFixture.tempDirFixture.createFile("relative-config/biome.jsonc", "{}")
        assertTrue(project.getBaseDirectories().any { it == file.parent.parent })
        for (path in listOf("relative-config/biome.jsonc", "relative-config")) {
            assertFalse("The IDE cwd must not contain the fixture", Files.exists(Path.of(path)))
            assertCanApply(path)
        }
    }

    fun testRelativeConfigCanResolveAgainstSecondaryContentRoot() {
        val directory = Files.createTempDirectory("biome-secondary-content-root-")
        val configDirectory = Files.createDirectory(directory.resolve("secondary-config"))
        val config = configDirectory.resolve("biome.jsonc")
        try {
            Files.writeString(config, "{}")
            val root = checkNotNull(VfsUtil.findFile(directory, true))
            ModuleRootModificationUtil.addContentRoot(myFixture.module, root.path)
            PlatformTestUtil.waitWithEventsDispatching("Secondary content root was not published", {
                project.getBaseDirectories().contains(root)
            }, 5)
            assertTrue(project.getBaseDirectories().contains(root))
            assertFalse(Files.exists(Path.of("secondary-config/biome.jsonc")))
            assertCanApply("secondary-config/biome.jsonc")
        } finally {
            Files.deleteIfExists(config)
            Files.deleteIfExists(configDirectory)
            Files.deleteIfExists(directory)
        }
    }

    fun testDefaultProjectAcceptsRelativeConfigWithoutResolvingIt() {
        defaultProjectPanel { configurable, component, defaultSettings ->
            val relative = "future-project/config/custom.jsonc"
            assertFalse(Files.exists(Path.of(relative)))
            configField(component).text = relative
            configurable.apply()
            assertEquals(relative, defaultSettings.configPath)
            assertEquals(ConfigurationMode.MANUAL, defaultSettings.configurationMode)
        }
    }

    fun testDefaultProjectRejectsMissingAbsoluteAndMalformedPaths() {
        defaultProjectPanel { configurable, component, defaultSettings ->
            val missing = Path.of(myFixture.tempDirPath, "missing-config.jsonc").toString()
            for (path in listOf(missing, "bad\u0000path")) {
                configField(component).text = path
                assertThrows(ConfigurationException::class.java) { configurable.apply() }
                assertEquals("", defaultSettings.configPath)
            }
        }
    }

    private fun defaultProjectPanel(test: (BiomeConfigurable, Component, BiomeSettings) -> Unit) {
        val defaultProject = ProjectManager.getInstance().defaultProject
        val defaultSettings = BiomeSettings.getInstance(defaultProject)
        val previous = XmlSerializer.deserialize(XmlSerializer.serialize(defaultSettings.state), BiomeSettingsState::class.java)
        val configurable = BiomeConfigurable(defaultProject)
        try {
            defaultSettings.loadState(BiomeSettingsState().apply { configurationMode = ConfigurationMode.MANUAL })
            val component = configurable.createComponent()!!
            configurable.reset()
            test(configurable, component, defaultSettings)
        } finally {
            configurable.disposeUIResources()
            defaultSettings.loadState(previous)
        }
    }

    fun testRelativeConfigCannotResolveOnlyAgainstIdeWorkingDirectory() {
        val directory = Files.createTempDirectory(Path.of(System.getProperty("user.dir"), "build"), "cwd-config-")
        val config = directory.resolve("biome.jsonc")
        try {
            Files.writeString(config, "{}")
            val relative = Path.of(System.getProperty("user.dir")).relativize(config).toString()
            assertTrue(Files.isRegularFile(Path.of(relative)))
            assertTrue(project.getBaseDirectories().none { Files.exists(it.toNioPath().resolve(relative)) })
            settings.configurationMode = ConfigurationMode.MANUAL
            settings.configPath = ""
            panel { configurable, component ->
                configField(component).text = relative
                assertThrows(ConfigurationException::class.java) { configurable.apply() }
                assertEquals("", settings.configPath)
            }
        } finally {
            Files.deleteIfExists(config)
            Files.deleteIfExists(directory)
        }
    }

    private fun assertCanApply(path: String) {
        settings.configurationMode = ConfigurationMode.MANUAL
        panel { configurable, component ->
            configField(component).text = path
            configurable.apply()
            assertEquals(path, settings.configPath)
            assertEquals(path, BiomePackage(project).configPath())
        }
    }

    fun testBlankManualPathCanApply() {
        val selectedDirectory = myFixture.tempDirFixture.createFile("selected/biome.json", "{}").parent.path
        for (blank in listOf("", " \t\n")) {
            settings.configurationMode = ConfigurationMode.MANUAL
            settings.configPath = selectedDirectory
            panel { configurable, component ->
                configField(component).text = blank
                configurable.apply()
                assertEquals(ConfigurationMode.MANUAL, settings.configurationMode)
                assertEquals("", settings.configPath)
                assertTrue(settings.state.configPath.isNullOrEmpty())
                assertEquals("", BiomePackage(project).configPath())
            }
        }
    }

    fun testHiddenInvalidInputDoesNotBlockAutomaticMode() = applyHiddenInput(ConfigurationMode.AUTOMATIC)

    fun testHiddenInvalidInputDoesNotBlockDisabledMode() = applyHiddenInput(ConfigurationMode.DISABLED)

    fun testNewManualSelectionIsValidatedBeforeApplyingMode() {
        settings.configurationMode = ConfigurationMode.AUTOMATIC
        settings.configPath = "missing-config"
        panel { configurable, component ->
            radios(component)[2].isSelected = true
            assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertEquals(ConfigurationMode.AUTOMATIC, settings.configurationMode)
            assertEquals("missing-config", settings.configPath)
        }
    }

    private fun applyHiddenInput(mode: ConfigurationMode) {
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.configPath = "bad\u0000path"
        panel { configurable, component ->
            radios(component)[if (mode == ConfigurationMode.AUTOMATIC) 1 else 0].isSelected = true
            assertEquals(ConfigurationMode.MANUAL, settings.configurationMode)
            configurable.apply()
            assertEquals(mode, settings.configurationMode)
        }
    }

    private fun panel(test: (BiomeConfigurable, Component) -> Unit) {
        val configurable = BiomeConfigurable(project)
        try {
            val component = configurable.createComponent()!!
            configurable.reset()
            test(configurable, component)
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun configField(component: Component): TextFieldWithBrowseButton =
        descendants(component).filterIsInstance<TextFieldWithBrowseButton>().toList().let {
            assertEquals(2, it.size)
            it[1]
        }

    private fun radios(component: Component): List<JRadioButton> =
        descendants(component).filterIsInstance<JRadioButton>().toList().also { assertEquals(3, it.size) }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }
}
