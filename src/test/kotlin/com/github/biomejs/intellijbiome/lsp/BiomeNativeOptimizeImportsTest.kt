package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.fixtures.StartupLspProcess
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.BiomeSettingsState
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.components.service
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.customization.LspOptimizeImportsSupport
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Current routing only: no optimizer customization or ownership override. */
class BiomeNativeOptimizeImportsTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    override fun tearDown() {
        try {
            project.service<BiomeServerService>().stopBiomeServer()
            BiomeSettings.getInstance(project).loadState(BiomeSettingsState())
        } finally { super.tearDown() }
    }

    fun testNativeJavaScriptOptimizerRetainsOwnershipAndDedicatedActionUsesBiomeKind() {
        val directory = Files.createDirectories(Path.of(myFixture.tempDirPath, "editor-peer"))
        val resource = StartupLspProcess::class.java.name.replace('.', '/') + ".class"
        val classes = directory.resolve("classes")
        val destination = classes.resolve(resource)
        Files.createDirectories(destination.parent)
        StartupLspProcess::class.java.classLoader.getResourceAsStream(resource)!!.use {
            Files.copy(it, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        val executable = directory.resolve("biome-fixture")
        val args = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classes.toString(), StartupLspProcess::class.java.name)
        Files.writeString(executable, "#!/bin/sh\nexport BIOME_EDITOR_FIXTURE_DIRECTORY=${quote(directory.toString())}\nexec ${args.joinToString(" ", transform = ::quote)} \"\$@\"\n")
        check(executable.toFile().setExecutable(true))
        myFixture.addFileToProject("biome.json", "{}")
        myFixture.addFileToProject("unused.js", "export const unused = 1;")
        myFixture.addFileToProject("used.js", "export const used = 1;")
        val file = myFixture.addFileToProject("index.js", "import { unused } from './unused';\nimport { used } from './used';\nconsole.log(used);\n").virtualFile
        assertTrue(file.isInLocalFileSystem)
        BiomeSettings.getInstance(project).apply {
            loadState(BiomeSettingsState())
            configurationMode = ConfigurationMode.MANUAL
            executablePath = executable.toString()
        }
        myFixture.configureFromExistingVirtualFile(file)
        waitUntilFileOpenedByLspServer(project, file, BiomeLspServerDescriptor::class.java.name)
        val server = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
        val optimizer = server.descriptor.lspCustomization.optimizeImportsCustomizer as LspOptimizeImportsSupport
        assertFalse(optimizer.shouldOptimizeImportsInThisFileExclusivelyByServer(server, file, true))
        val uri = Json.encodeToString(server.getDocumentIdentifier(file).uri)
        Files.writeString(directory.resolve("code-action-result.json"), """
            [{"title":"Controlled Biome imports","kind":"source.organizeImports.biome","edit":{"changes":{$uri:[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}},"newText":"// Biome imports\n"}]}}}]
        """.trimIndent())
        myFixture.performEditorAction("OptimizeImports")
        PlatformTestUtil.waitWithEventsDispatching("Native optimizer did not remove the unused import", {
            !myFixture.editor.document.text.contains("unused")
        }, 10)
        val nativeText = myFixture.editor.document.text
        assertTrue(nativeText.contains("used"))
        assertFalse("Native JS optimization must not also send a Biome request", Files.exists(directory.resolve("code-action-request.json")))
        println("BIOME_NATIVE_OPTIMIZE_IMPORTS_RESULT=" + Json.encodeToString(nativeText))

        myFixture.performEditorAction("BiomeSortImportAction")
        PlatformTestUtil.waitWithEventsDispatching("Dedicated Biome action did not apply its response", {
            myFixture.editor.document.text.startsWith("// Biome imports\n")
        }, 10)
        assertEquals("// Biome imports\n$nativeText", myFixture.editor.document.text)
        val request = Json.parseToJsonElement(Files.readString(directory.resolve("code-action-request.json"))).jsonObject
        val context = request.getValue("params").jsonObject.getValue("context").jsonObject
        assertEquals(listOf("source.organizeImports.biome"), context.getValue("only").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("2", context.getValue("triggerKind").jsonPrimitive.content)
        println("BIOME_DEDICATED_OPTIMIZE_IMPORTS_REQUEST=$request")
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
