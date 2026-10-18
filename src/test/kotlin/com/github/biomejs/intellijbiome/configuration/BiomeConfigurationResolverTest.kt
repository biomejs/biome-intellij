package com.github.biomejs.intellijbiome.configuration

import com.github.biomejs.intellijbiome.configuration.BiomeConfigurationResolution.Found
import com.github.biomejs.intellijbiome.configuration.BiomeConfigurationResolution.Unavailable
import com.github.biomejs.intellijbiome.configuration.BiomeConfigurationResolution.Reason
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import java.nio.file.Files
import java.util.concurrent.Callable

class BiomeConfigurationResolverTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private val automatic = BiomeConfigurationSettings(ConfigurationMode.AUTOMATIC)

    fun testNearestSourceConfigIncludesInheritedAndMalformedConfigurations() {
        val root = myFixture.tempDirFixture.createFile("biome.json", "{}")
        val child = myFixture.tempDirFixture.createFile("nested/biome.jsonc", "{}")
        val source = myFixture.tempDirFixture.createFile("nested/src/file.js", "let value=1;")
        for (content in listOf("""{"root":false}""", """{"extends":"//"}""", "{broken")) {
            WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(child, content) }
            assertEquals(Found(child, root.parent), resolve(source))
        }
    }

    fun testJsonTakesPrecedenceForFilesAndDirectories() {
        val jsonc = myFixture.tempDirFixture.createFile("biome.jsonc", "{}")
        val json = myFixture.tempDirFixture.createFile("biome.json", "{}")
        val source = myFixture.tempDirFixture.createFile("src/file.js", "")
        assertEquals(Found(json, json.parent), resolve(source))
        assertEquals(Found(json, json.parent), resolve(jsonc))
        assertEquals(Found(json, json.parent), resolve(json.parent))
    }

    fun testManualExactFilesAndDirectories() {
        val source = myFixture.tempDirFixture.createFile("src/file.js", "")
        val custom = myFixture.tempDirFixture.createFile("config/custom.jsonc", "{broken")
        val jsonc = myFixture.tempDirFixture.createFile("config/biome.jsonc", "{}")
        val json = myFixture.tempDirFixture.createFile("config/biome.json", "{}")
        val base = source.parent.parent
        for (path in listOf(custom.path, "config/custom.jsonc")) {
            assertEquals(Found(custom, base), resolve(source, manual(path)))
        }
        for (path in listOf(json.parent.path, "config")) {
            assertEquals(Found(json, base), resolve(source, manual(path)))
        }
        assertEquals(Found(jsonc, base), resolve(source, manual(jsonc.path)))
    }

    fun testManualBlankPathUsesAutomaticDiscovery() {
        val config = myFixture.tempDirFixture.createFile("biome.json", "{}")
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        assertEquals(Found(config, config.parent), resolve(source, manual(" \t")))
    }

    fun testRelativeOverridesBelongToTheSelectedProjectBase() {
        val first = myFixture.tempDirFixture.createFile("config/custom.jsonc", "{}")
        val firstSource = myFixture.tempDirFixture.createFile("file.js", "")
        val secondBase = secondBase()
        val second = WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
            VfsUtil.createDirectoryIfMissing(secondBase, "config")
        }
        val secondConfig = WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
            second.createChildData(this, "custom.jsonc")
        }
        val secondSource = WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
            secondBase.createChildData(this, "file.js")
        }
        val settings = manual("config/custom.jsonc")
        assertEquals(Found(first, firstSource.parent), resolve(firstSource, settings))
        assertEquals(Found(secondConfig, secondBase), resolve(secondSource, settings))
        val ambiguous = resolve(null, settings)
        assertTrue(ambiguous.toString(), ambiguous is BiomeConfigurationResolution.Ambiguous)
        assertEquals(setOf(Found(first, firstSource.parent), Found(secondConfig, secondBase)),
            (ambiguous as BiomeConfigurationResolution.Ambiguous).candidates.toSet())
    }

    fun testExternalManualFileRetainsOwningProjectBase() {
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        val external = Files.createTempDirectory("biome-external-config-")
        Disposer.register(testRootDisposable) { FileUtil.delete(external.toFile()) }
        val path = Files.writeString(external.resolve("custom.jsonc"), "{}")
        val config = checkNotNull(VfsUtil.findFile(path, true))
        assertEquals(Found(config, source.parent), resolve(source, manual(config.path)))
        secondBase()
        val result = resolve(null, manual(config.path))
        assertTrue("A shared external file must retain ambiguous base ownership", result is BiomeConfigurationResolution.Ambiguous)
    }

    fun testDeletedAndMovedConfigurationIsNotCached() {
        val root = myFixture.tempDirFixture.createFile("biome.json", "{}")
        val nested = myFixture.tempDirFixture.createFile("nested/biome.jsonc", "{}")
        val source = myFixture.tempDirFixture.createFile("nested/file.js", "")
        assertEquals(Found(nested, root.parent), resolve(source))
        WriteCommandAction.runWriteCommandAction(project) { nested.move(this, root.parent) }
        assertEquals(Found(root, root.parent), resolve(source))
        WriteCommandAction.runWriteCommandAction(project) { root.delete(this) }
        assertEquals(Found(nested, nested.parent), resolve(source))
        WriteCommandAction.runWriteCommandAction(project) { nested.delete(this) }
        assertEquals(Unavailable(Reason.MISSING_CONFIGURATION), resolve(source))
    }

    fun testSnapshotsAndModeChangesDoNotRetainOldSettings() {
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        val first = myFixture.tempDirFixture.createFile("first.json", "{}")
        val second = myFixture.tempDirFixture.createFile("second.jsonc", "{}")
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.configPath = first.path
        val snapshot = BiomeConfigurationSettings.capture(project)
        settings.configPath = second.path
        assertEquals(Found(first, source.parent), resolve(source, snapshot))
        assertEquals(Found(second, source.parent), resolve(source, BiomeConfigurationSettings.capture(project)))
        settings.configurationMode = ConfigurationMode.DISABLED
        assertEquals(Unavailable(Reason.DISABLED), resolve(source, BiomeConfigurationSettings.capture(project)))
    }

    fun testInvalidManualSelectionDoesNotFallBackToAutomatic() {
        myFixture.tempDirFixture.createFile("biome.json", "{}")
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        myFixture.tempDirFixture.createFile("other.txt", "{}")
        myFixture.tempDirFixture.findOrCreateDir("empty")
        for (path in listOf("missing", "other.txt", "empty", "bad\u0000path")) {
            assertEquals(path, Unavailable(Reason.MISSING_CONFIGURATION), resolve(source, manual(path)))
        }
    }

    fun testTargetNativePathDoesNotOpenAnUnrelatedHostFile() {
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        val host = myFixture.tempDirFixture.createFile("custom.json", "{}")
        val settings = manual(host.path).copy(interpreter = WslNodeInterpreter("BiomeNonexistentTestDistribution", "/usr/bin/node"))
        val result = resolve(source, settings)
        assertTrue("A WSL override must not open the host's same-named file: $result", result is Unavailable)
    }

    fun testExplicitLocalExecutableDoesNotBorrowConfiguredWslTarget() {
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        val host = myFixture.tempDirFixture.createFile("custom.json", "{}")
        val settings = manual(host.path).copy(executablePath = "/native/biome",
            interpreter = WslNodeInterpreter("BiomeNonexistentTestDistribution", "/usr/bin/node"))
        assertEquals(Found(host, source.parent), resolve(source, settings))
    }

    fun testExplicitWslExecutableDoesNotBorrowAnotherInterpreterDistribution() {
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        val settings = manual("//wsl.localhost/BiomePrimary/home/custom.json").copy(
            executablePath = "//wsl$/BiomePrimary/usr/bin/biome",
            interpreter = WslNodeInterpreter("BiomeForeign", "/usr/bin/node"))
        assertEquals(Unavailable(Reason.MISSING_CONFIGURATION), resolve(source, settings))
        assertEquals(Unavailable(Reason.UNRESOLVED_TARGET), resolve(source,
            settings.copy(configPath = "//wsl$/BiomeForeign/home/custom.json")))
    }

    fun testAutomaticLookupDoesNotLeaveTheProjectBase() {
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        val parent = source.parent.parent
        val config = WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
            parent.createChildData(this, "biome.json")
        }
        try {
            assertEquals(Unavailable(Reason.MISSING_CONFIGURATION), resolve(source))
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { config.delete(this) }
        }
    }

    private fun resolve(file: VirtualFile?, settings: BiomeConfigurationSettings = automatic) =
        resolveBiomeConfigurationForTest(project, file, settings)

    private fun manual(path: String) = BiomeConfigurationSettings(ConfigurationMode.MANUAL, path)

    private fun secondBase(): VirtualFile {
        val path = Files.createTempDirectory("biome-second-base-")
        Disposer.register(testRootDisposable) { FileUtil.delete(path.toFile()) }
        val base = checkNotNull(VfsUtil.findFile(path, true))
        ModuleRootModificationUtil.addContentRoot(myFixture.module, base.path)
        PlatformTestUtil.waitWithEventsDispatching("Secondary base was not published", {
            project.getBaseDirectories().contains(base)
        }, 5)
        return base
    }
}

internal fun resolveBiomeConfigurationForTest(project: Project, file: VirtualFile?, settings: BiomeConfigurationSettings): BiomeConfigurationResolution {
    val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
        BiomeConfigurationResolver.resolve(project, file, settings)
    })
    PlatformTestUtil.waitWithEventsDispatching("Background configuration resolution did not finish", { future.isDone }, 10)
    return future.get()
}
