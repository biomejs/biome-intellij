package com.github.biomejs.intellijbiome.codestyle

import com.github.biomejs.intellijbiome.launcher.findNativeBiomeExecutable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.ExecutionException
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeCodeStyleCliTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private lateinit var root: Path

    override fun setUp() {
        super.setUp()
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.MANUAL
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        val directory = myFixture.tempDirFixture.getFile(".") ?: error("Missing project directory")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, directory)
        root = Path.of(directory.path)
        BiomeSettings.getInstance(project).executablePath = findNativeBiomeExecutable(root).toString()
    }

    fun testBiomeResolvesSavedConfigurationForTheSelectedFile() {
        val file = write("selected.ts", "let original=1;\n")
        val originalScheme = CodeStyleSettingsManager.getInstance(project).currentSettings
        val originalSettings = originalScheme.clone()
        write("biome.jsonc", """{
            // This is JSONC, including a trailing comma.
            "formatter": {"indentStyle": "space", "indentWidth": 4,},
            "javascript": {"formatter": {"quoteStyle": "single", "semicolons": "asNeeded"}},
        }""")
        assertEquals(BiomeCodeStylePreferences(false, 4, false, false), read(file))

        write("preset.jsonc", """{"formatter":{"indentStyle":"space","indentWidth":3,}}""")
        write("biome.jsonc", """{"extends":["./preset.jsonc"]}""")
        assertEquals(BiomeCodeStylePreferences(false, 3, true, true), read(file))

        write("node_modules/style-preset/package.json",
            """{"name":"style-preset","exports":{"./biome":"./biome.json"}}""")
        write("node_modules/style-preset/biome.json", """{"formatter":{"indentStyle":"space","indentWidth":5}}""")
        write("biome.jsonc", """{"extends":["style-preset/biome"]}""")
        assertEquals(BiomeCodeStylePreferences(false, 5, true, true), read(file))

        write("biome.jsonc", """{
            "formatter":{"indentStyle":"space","indentWidth":3},
            "overrides":[{"includes":["**/*.ts"],"javascript":{"formatter":{"indentWidth":6,"quoteStyle":"single"}}}]
        }""")
        assertEquals(BiomeCodeStylePreferences(false, 6, false, true), read(file))
        assertEquals(BiomeCodeStylePreferences(false, 3, true, true), read(write("selected.js", "let other=1;")))

        write("biome.jsonc", """{
            "formatter":{"indentStyle":"space","indentWidth":3},
            "javascript":{"formatter":{"quoteStyle":"single"}}
        }""")
        val child = write("child/selected.ts", "let child=1;")
        write("child/biome.json", """{"extends":"//","javascript":{"formatter":{"semicolons":"asNeeded"}}}""")
        assertEquals(BiomeCodeStylePreferences(false, 3, false, false), read(child))
        write("child/biome.json", """{"root":true,"formatter":{"indentStyle":"space","indentWidth":7}}""")
        assertEquals(BiomeCodeStylePreferences(false, 7, true, true), read(child))

        write(".editorconfig", "root = true\n[*]\nindent_style = space\nindent_size = 6\n")
        write("biome.jsonc", """{"formatter":{"useEditorconfig":true}}""")
        assertEquals(BiomeCodeStylePreferences(false, 6, true, true), read(file))
        write("biome.jsonc", """{"formatter":{"useEditorconfig":true,"indentWidth":4}}""")
        assertEquals(BiomeCodeStylePreferences(false, 4, true, true), read(file))

        write("manual config.json", """{"formatter":{"indentStyle":"space","indentWidth":8}}""")
        BiomeSettings.getInstance(project).configPath = root.resolve("manual config.json").toString()
        assertEquals(BiomeCodeStylePreferences(false, 8, true, true), read(file))

        assertEquals("Reading preferences must not format the selected document", "let original=1;\n", Files.readString(file.toNioPath()))
        assertSame(originalScheme, CodeStyleSettingsManager.getInstance(project).currentSettings)
        assertEquals(originalSettings, originalScheme)
    }

    fun testIgnoredDisabledAndInvalidConfigurationCannotBeImported() {
        val file = write("selected.ts", "let original=1;\n")
        for (configuration in listOf(
            """{"formatter":{"includes":["!selected.ts"]}}""",
            """{"formatter":{"enabled":false}}""",
            """{"extends":["./missing.json"]}""",
        )) {
            write("biome.json", configuration)
            try {
                read(file)
                fail("An ignored, disabled or invalid formatter must not produce importable preferences")
            } catch (_: ExecutionException) {
                assertEquals("let original=1;\n", Files.readString(file.toNioPath()))
            }
        }
    }

    fun testConfiguredNpmLauncherReturnsTheWholeSample() {
        val node = EnvironmentUtil.getEnvironmentMap().getValue("PATH").split(File.pathSeparator)
            .map { Path.of(it, "node") }.firstOrNull(Files::isExecutable) ?: error("Node is required")
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(
            NodeJsInterpreterRef.create(NodeJsLocalInterpreter(node.toString())), testRootDisposable)
        BiomeSettings.getInstance(project).executablePath = root.resolve("node_modules/@biomejs/biome/bin/biome").toString()
        write("biome.json", """{"formatter":{"indentStyle":"space","indentWidth":6}}""")
        assertEquals(BiomeCodeStylePreferences(false, 6, true, true), read(write("selected.ts", "let original=1;")))
    }

    fun testFileSymlinkKeepsTheSelectedFilenameForOverrides() {
        write("biome.json", """{
            "formatter":{"indentStyle":"space","indentWidth":3},
            "overrides":[{"includes":["**/*.ts"],"formatter":{"indentWidth":6}}]
        }""")
        val target = write("target.js", "let original=1;")
        val selectedPath = root.resolve("selected-link.ts")
        Files.createSymbolicLink(selectedPath, target.toNioPath().fileName)
        val selected = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(selectedPath)
            ?: error("Missing selected symlink")
        assertEquals(BiomeCodeStylePreferences(false, 6, true, true), read(selected))
        assertEquals("let original=1;", Files.readString(target.toNioPath()))
    }

    private fun read(file: VirtualFile): BiomeCodeStylePreferences =
        runWithModalProgressBlocking(project, "Read test code style") { readBiomeCodeStyle(project, file) }

    private fun write(relative: String, text: String): VirtualFile {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return (LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: error("Missing $path"))
            .also { it.refresh(false, false) }
    }
}
