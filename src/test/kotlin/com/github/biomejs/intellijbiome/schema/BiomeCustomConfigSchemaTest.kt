package com.github.biomejs.intellijbiome.schema

import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.jetbrains.jsonSchema.JsonMappingKind
import com.jetbrains.jsonSchema.JsonSchemaMappingsProjectConfiguration
import com.jetbrains.jsonSchema.UserDefinedJsonSchemaConfiguration
import com.jetbrains.jsonSchema.extension.ContentAwareJsonSchemaFileProvider
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion
import com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JRadioButton

class BiomeCustomConfigSchemaTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private val settings get() = BiomeSettings.getInstance(project)
    private val json get() = JsonSchemaService.Impl.get(project)

    override fun setUp() {
        super.setUp()
        VfsUtil.findFile(Files.createDirectories(Path.of(project.basePath!!)), true)
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.supportedExtensions = mutableListOf(".js")
        myFixture.enableInspections(JsonSchemaComplianceInspection())
        val schema = myFixture.addFileToProject("fallback-schema.json", schemaText("selectedOnly")).virtualFile
        val providers = ContentAwareJsonSchemaFileProvider.EP_NAME.extensionList
        assertTrue("The plugin must register its schema provider", providers.any { it is BiomeCustomConfigSchemaProvider })
        ExtensionTestUtil.maskExtensions(ContentAwareJsonSchemaFileProvider.EP_NAME, providers.map { provider ->
            if (provider !is BiomeCustomConfigSchemaProvider) provider else object : ContentAwareJsonSchemaFileProvider {
                override fun getSchemaFile(file: PsiFile): VirtualFile? {
                    val published = provider.getSchemaFile(file) ?: return null
                    assertEquals("https://biomejs.dev/schemas/2.5.15/schema.json", published.url)
                    // Keep provider selection and platform analysis real; only replace remote schema bytes.
                    return schema
                }
            }
        }, testRootDisposable)
    }

    fun testPublishedFallbackNeedsNoPackageOrServer() {
        openConfig()
        assertEquals("https://biomejs.dev/schemas/2.5.15/schema.json",
            BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file)?.url)
        assertContainsElements(completions(), "selectedOnly")
        assertTrue(LspServerManager.getInstance(project)
            .getServersForProvider(BiomeLspServerSupportProvider::class.java).isEmpty())
    }

    fun testCustomJsoncCompletesAndValidates() {
        openConfig("config/custom.jsonc", "{\n// custom configuration\n\"\": false\n}")
        assertContainsElements(completions(), "selectedOnly")
        setText("{\"selectedOnly\": \"wrong type\"}")
        assertBooleanDiagnostic()
        setText("{\"notABiomeOption\":true}")
        assertTrue("Unknown options must be reported", myFixture.doHighlighting().any {
            it.description?.contains("notABiomeOption") == true
        })
    }

    fun testOnlyTheSelectedAbsoluteOrRelativePathReceivesFallback() {
        val config = openConfig()
        assertNotNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
        settings.configPath = "config/./unused/../custom.json"
        assertNotNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
        val other = myFixture.addFileToProject("other/custom.json", "{}").virtualFile
        myFixture.configureFromExistingVirtualFile(other)
        assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
        settings.configPath = config.path
        assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
    }

    fun testStandardFilenamesAndNonJsonFilesReceiveNoFallback() {
        for (name in listOf("biome.json", "biome.jsonc", "custom.js")) {
            openConfig(name)
            assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
        }
    }

    fun testOnlyNonblankValidManualSelectionReceivesFallback() {
        val config = openConfig()
        for (mode in listOf(ConfigurationMode.AUTOMATIC, ConfigurationMode.DISABLED)) {
            settings.configurationMode = mode
            assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
        }
        settings.configurationMode = ConfigurationMode.MANUAL
        for (path in listOf("", " \t\n", "bad\u0000path", config.parent.path)) {
            settings.configPath = path
            assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
        }
    }

    fun testUnsavedExplicitSchemaAdditionAndRemoval() {
        myFixture.addFileToProject("explicit-schema.json", schemaText("associatedOnly"))
        openConfig()
        assertContainsElements(completions(), "selectedOnly")
        setText("{\"\$schema\":\"../explicit-schema.json\",\"\":false}")
        assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
        assertContainsElements(completions(), "associatedOnly")
        assertFalse("selectedOnly" in completions())
        setText("{\"\":false}")
        assertNotNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
        assertContainsElements(completions(), "selectedOnly")
    }

    fun testUnresolvedEmptyAndNonStringExplicitSchemaSuppressFallback() {
        openConfig()
        assertContainsElements(completions(), "selectedOnly")
        for (value in listOf("\"./missing-schema.json\"", "\"\"", "42", "null")) {
            setText("{\"\$schema\":$value,\"\":false}")
            assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
            assertFalse("An explicit schema must suppress Biome completion: $value", "selectedOnly" in completions())
        }
    }

    fun testFileMappingPrecedesCustomAssociation() = mappingPrecedence(JsonMappingKind.File)
    fun testPatternMappingPrecedesCustomAssociation() = mappingPrecedence(JsonMappingKind.Pattern)
    fun testDirectoryMappingPrecedesCustomAssociation() = mappingPrecedence(JsonMappingKind.Directory)

    private fun mappingPrecedence(kind: JsonMappingKind) {
        val explicit = myFixture.addFileToProject("explicit-schema.json", schemaText("associatedOnly")).virtualFile
        val config = openConfig("config/custom.jsonc")
        assertContainsElements(completions(), "selectedOnly")
        val path = when (kind) {
            JsonMappingKind.Pattern -> "*.jsonc"
            JsonMappingKind.Directory -> Path.of(project.basePath!!).relativize(config.parent.toNioPath()).toString()
            else -> Path.of(project.basePath!!).relativize(config.toNioPath()).toString()
        }
        val mapping = UserDefinedJsonSchemaConfiguration("user", JsonSchemaVersion.SCHEMA_7, explicit.path, false,
            listOf(UserDefinedJsonSchemaConfiguration.Item(path, kind)))
        val mappings = JsonSchemaMappingsProjectConfiguration.getInstance(project)
        mappings.addConfiguration(mapping)
        json.reset()
        try {
            val items = completions()
            assertContainsElements(items, "associatedOnly")
            assertFalse("selectedOnly" in items)
            setText("{\"associatedOnly\":\"wrong\"}")
            assertBooleanDiagnostic()
        } finally {
            mappings.removeConfiguration(mapping)
            json.reset()
        }
        setText("{\"\":false}")
        assertContainsElements(completions(), "selectedOnly")
    }

    fun testIgnoredFilePrecedesCustomAssociation() {
        val config = openConfig()
        assertContainsElements(completions(), "selectedOnly")
        val mappings = JsonSchemaMappingsProjectConfiguration.getInstance(project)
        mappings.markAsIgnored(config)
        json.reset()
        try {
            assertNull(json.getSchemaObject(myFixture.file))
            assertFalse("selectedOnly" in completions())
        } finally {
            mappings.unmarkAsIgnored(config)
            json.reset()
        }
    }

    fun testSettingsApplyRefreshesCachedSchemaAfterPathChangeWithoutEditingOrReopening() {
        val first = openConfig()
        val second = myFixture.addFileToProject("config/second.jsonc", "{}").virtualFile
        assertContainsElements(completions(), "selectedOnly")
        assertNotNull(json.getSchemaObject(myFixture.file))
        val editor = myFixture.editor
        val stamp = editor.document.modificationStamp
        applySettings(path = second.path)
        assertSame(editor, myFixture.editor)
        assertEquals(stamp, editor.document.modificationStamp)
        assertEquals(first, myFixture.file.virtualFile)
        assertNull(json.getSchemaObject(myFixture.file))
        assertFalse("selectedOnly" in completions())
        applySettings(path = first.path)
        assertEquals(stamp, editor.document.modificationStamp)
        assertNotNull(json.getSchemaObject(myFixture.file))
        assertContainsElements(completions(), "selectedOnly")
    }

    fun testSettingsApplyRefreshesCachedSchemaAfterModeChangeWithoutEditingOrReopening() {
        val config = openConfig()
        val editor = myFixture.editor
        val stamp = editor.document.modificationStamp
        for (mode in listOf(ConfigurationMode.AUTOMATIC, ConfigurationMode.DISABLED)) {
            assertContainsElements(completions(), "selectedOnly")
            assertNotNull(json.getSchemaObject(myFixture.file))
            applySettings(mode = mode)
            assertSame(editor, myFixture.editor)
            assertEquals(stamp, editor.document.modificationStamp)
            assertEquals(config, myFixture.file.virtualFile)
            assertNull(json.getSchemaObject(myFixture.file))
            assertFalse("selectedOnly" in completions())
            applySettings(mode = ConfigurationMode.MANUAL)
            assertEquals(stamp, editor.document.modificationStamp)
            assertNotNull(json.getSchemaObject(myFixture.file))
        }
        assertContainsElements(completions(), "selectedOnly")
    }

    fun testWslAliasesShareConfigurationIdentityWhileLinuxPathCaseIsPreserved() {
        assertEquals("//wsl.localhost/ubuntu/home/Config.JSONC",
            schemaConfigurationPath("//wsl$/Ubuntu/home/Config.JSONC"))
        assertEquals("//wsl.localhost/ubuntu/home/Config.JSONC",
            schemaConfigurationPath("//wsl.localhost/UBUNTU/home/Config.JSONC"))
        assertFalse(schemaConfigurationPath("//wsl$/Ubuntu/home/Config.JSONC") ==
            schemaConfigurationPath("//wsl.localhost/ubuntu/home/config.jsonc"))
        assertFalse(schemaConfigurationPath("//wsl$/Ubuntu/home/config.jsonc") ==
            schemaConfigurationPath("//wsl$/Debian/home/config.jsonc"))
        assertEquals("//fileserver/share/Config.JSONC", schemaConfigurationPath("//fileserver/share/Config.JSONC"))
    }

    fun testTargetNativeWslSelectionUsesThePublishedFallback() {
        for (actual in listOf("//wsl$/Ubuntu/home/me/custom.jsonc", "//wsl.localhost/Debian/home/me/custom.jsonc")) {
            val file = wslConfig(actual)
            for (path in listOf("/home/me/custom.jsonc", "/home/me/../me/custom.jsonc")) {
                settings.configPath = path
                assertEquals("https://biomejs.dev/schemas/2.5.15/schema.json",
                    BiomeCustomConfigSchemaProvider().getSchemaFile(file)?.url)
            }
            settings.configPath = "/home/me/Custom.jsonc"
            assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(file))
        }
    }

    fun testWslFallbackIgnoresExecutableAndInterpreterSelection() {
        val file = wslConfig()
        settings.configPath = "/home/me/custom.jsonc"
        val manager = NodeJsInterpreterManager.getInstance(project)
        for (reference in listOf(NodeJsInterpreterRef.create(WslNodeInterpreter("Debian", "/usr/bin/node")),
            NodeJsInterpreterRef.create(NodeJsLocalInterpreter("/missing/node")))) {
            manager.setInterpreterRef(reference)
            for (executable in listOf("", "biome", "/usr/bin/biome", "bin/biome", "//wsl$/Debian/usr/bin/biome")) {
                settings.executablePath = executable
                assertEquals("https://biomejs.dev/schemas/2.5.15/schema.json",
                    BiomeCustomConfigSchemaProvider().getSchemaFile(file)?.url)
            }
        }
    }

    fun testWslUncMatchingPreservesAliasesAndLinuxPathCaseOnEveryHost() {
        val file = wslConfig()
        for (path in listOf("//wsl$/UBUNTU/home/me/../me/custom.jsonc", "//wsl$/Ubuntu//home/me/custom.jsonc")) {
            settings.configPath = path
            assertNotNull(BiomeCustomConfigSchemaProvider().getSchemaFile(file))
        }
        settings.configPath = "//wsl$/Ubuntu/home/me/Custom.jsonc"
        assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(file))
        settings.configPath = "//wsl$/Debian/home/me/custom.jsonc"
        assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(file))
    }

    private fun wslConfig(path: String = "//wsl.localhost/Ubuntu/home/me/custom.jsonc"): PsiFile {
        val config = object : LightVirtualFile("custom.jsonc", "{}") {
            override fun getPath() = path
        }
        return PsiManager.getInstance(project).findFile(config)!!
    }

    private fun applySettings(path: String = settings.configPath, mode: ConfigurationMode = settings.configurationMode) {
        val configurable = BiomeConfigurable(project)
        try {
            val component = configurable.createComponent()!!
            configurable.reset()
            descendants(component).filterIsInstance<TextFieldWithBrowseButton>().toList()[1].text = path
            val index = when (mode) {
                ConfigurationMode.DISABLED -> 0
                ConfigurationMode.AUTOMATIC -> 1
                ConfigurationMode.MANUAL -> 2
            }
            descendants(component).filterIsInstance<JRadioButton>().toList()[index].isSelected = true
            configurable.apply()
            assertEquals(path, settings.configPath)
            assertEquals(mode, settings.configurationMode)
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }

    private fun openConfig(path: String = "config/custom.json", text: String = "{\"\":false}"): VirtualFile {
        val config = myFixture.addFileToProject(path, text).virtualFile
        settings.configPath = config.path
        myFixture.configureFromExistingVirtualFile(config)
        return config
    }

    private fun setText(text: String) {
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText(text) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun assertBooleanDiagnostic() {
        assertTrue("The schema must validate the custom file", myFixture.doHighlighting().any {
            it.description?.contains("boolean", ignoreCase = true) == true
        })
    }

    private fun completions(): List<String> {
        val text = myFixture.editor.document.text
        myFixture.editor.caretModel.moveToOffset(text.indexOf("\"\"") + 1)
        return myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
    }

    private fun schemaText(key: String) = """
        {"type":"object","properties":{"$key":{"type":"boolean"},"otherOption":{"type":"boolean"}},"additionalProperties":false}
    """.trimIndent()
}
