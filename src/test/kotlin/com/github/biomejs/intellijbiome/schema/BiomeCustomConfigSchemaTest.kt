package com.github.biomejs.intellijbiome.schema

import com.github.biomejs.intellijbiome.lsp.QueuedDiscoveryDispatcher
import com.github.biomejs.intellijbiome.lsp.configureLocalNodeForRuntimeTests
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.jetbrains.jsonSchema.JsonMappingKind
import com.jetbrains.jsonSchema.JsonSchemaMappingsProjectConfiguration
import com.jetbrains.jsonSchema.UserDefinedJsonSchemaConfiguration
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion
import com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class BiomeCustomConfigSchemaTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private val settings get() = BiomeSettings.getInstance(project)
    private val associations get() = project.service<BiomeSchemaAssociationService>()
    private val json get() = JsonSchemaService.Impl.get(project)

    override fun setUp() {
        super.setUp()
        VfsUtil.findFile(Files.createDirectories(java.nio.file.Path.of(project.basePath!!)), true)
        settings.configurationMode = ConfigurationMode.MANUAL
        // Schema assistance must not depend on launching the synthetic npm bin as an LSP server.
        settings.supportedExtensions = mutableListOf(".js")
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
        myFixture.enableInspections(JsonSchemaComplianceInspection())
    }

    fun testCustomJsoncCompletesAndValidatesFromLocalPackage() {
        val schema = createPackage()
        openConfig("config/custom.jsonc", "{\n// offline configuration\n\"\": false\n}")
        awaitSchema(schema)
        assertContainsElements(completions(), "selectedOnly")
        setText("{\"selectedOnly\": \"wrong type\"}")
        assertBooleanDiagnostic()
        setText("{\"notABiomeOption\":true}")
        assertTrue("Unknown options must be reported", myFixture.doHighlighting().any {
            it.description?.contains("notABiomeOption") == true
        })
    }

    fun testCustomJsonUsesOnlyTheSelectedExecutablePackage() {
        createPackage()
        val chosen = createPackage("other-tool", "chosenOnly")
        openConfig()
        awaitSchema(chosen)
        val items = completions()
        assertContainsElements(items, "chosenOnly")
        assertFalse(items.contains("selectedOnly"))
    }

    fun testImplicitExecutableDiscoversTheProjectPackageWithoutAProcess() {
        val schema = createPackage()
        settings.executablePath = ""
        openConfig()
        awaitSchema(schema)
        assertContainsElements(completions(), "selectedOnly")
        assertTrue("Schema discovery must not start a language server",
            com.intellij.platform.lsp.api.LspServerManager.getInstance(project)
                .getServersForProvider(com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider::class.java).isEmpty())
    }

    fun testUnselectedAndStandardFilenamesReceiveNoCustomFallback() {
        val schema = createPackage()
        val selected = openConfig()
        awaitSchema(schema)
        for (name in listOf("other.json", "biome.json", "biome.jsonc")) {
            val file = myFixture.addFileToProject(name, "{}").virtualFile
            myFixture.configureFromExistingVirtualFile(file)
            refresh()
            assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
        }
        assertEquals(selected.path, settings.configPath)
    }

    fun testSelectingAStandardFilenamePreservesPlatformProviders() {
        createPackage()
        openConfig("biome.jsonc")
        refresh()
        assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
    }

    fun testUnsavedExplicitSchemaAdditionAndRemoval() {
        val schema = createPackage()
        myFixture.addFileToProject("explicit-schema.json", schemaText("associatedOnly"))
        openConfig()
        awaitSchema(schema)
        setText("{\"\$schema\":\"../explicit-schema.json\",\"\":false}")
        assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
        assertContainsElements(completions(), "associatedOnly")
        setText("{\"\":false}")
        assertEquals(schema, BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
        assertContainsElements(completions(), "selectedOnly")
    }

    fun testUnresolvedEmptyAndNonStringExplicitSchemaSuppressFallback() {
        val schema = createPackage()
        openConfig()
        awaitSchema(schema)
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
        val schema = createPackage()
        val explicit = myFixture.addFileToProject("explicit-schema.json", schemaText("associatedOnly")).virtualFile
        val config = openConfig("config/custom.jsonc")
        awaitSchema(schema)
        val path = when (kind) {
            JsonMappingKind.Pattern -> "*.jsonc"
            JsonMappingKind.Directory -> java.nio.file.Path.of(project.basePath!!).relativize(config.parent.toNioPath()).toString()
            else -> java.nio.file.Path.of(project.basePath!!).relativize(config.toNioPath()).toString()
        }
        val mapping = UserDefinedJsonSchemaConfiguration("user", JsonSchemaVersion.SCHEMA_7, explicit.path, false,
            listOf(UserDefinedJsonSchemaConfiguration.Item(path, kind)))
        val mappings = JsonSchemaMappingsProjectConfiguration.getInstance(project)
        assertTrue("Mapping $kind path=$path basePath=${project.basePath} baseDir=${project.baseDir} config=$config",
            mapping.calculatedPatterns.any { it.process(project, config) })
        mappings.addConfiguration(mapping)
        json.reset()
        try {
            assertContainsElements(completions(), "associatedOnly")
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
        val schema = createPackage()
        val config = openConfig()
        awaitSchema(schema)
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

    fun testSchemaContentChangeAtSameUrlRefreshesCompletionAndValidation() {
        val schema = createPackage()
        openConfig()
        awaitSchema(schema)
        assertContainsElements(completions(), "selectedOnly")
        setText("{\"\":false}")
        WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(schema, schemaText("replacementOnly")) }
        awaitSchema(schema)
        assertContainsElements(completions(), "replacementOnly")
        setText("{\"replacementOnly\":\"wrong\"}")
        assertBooleanDiagnostic()
    }

    fun testRelativeManualPathAndSettingsChangesDiscardOldAssociation() {
        val schema = createPackage()
        val first = openConfig("first.jsonc")
        awaitSchema(schema)
        val second = myFixture.addFileToProject("config/second.jsonc", "{}").virtualFile
        settings.configPath = "config/second.jsonc"
        refresh()
        assertNull(associations.schemaFor(first))
        assertEquals(schema, associations.schemaFor(second))
        settings.configurationMode = ConfigurationMode.DISABLED
        refresh()
        assertNull(associations.schemaFor(second))
    }

    fun testForeignStandaloneAndPathExecutablesDoNotBorrowTheNearbyPackage() {
        createPackage()
        val foreign = myFixture.addFileToProject("foreign/biome", "#!/bin/sh\n").virtualFile
        val config = openConfig()
        for (executable in listOf(foreign.path, "biome", "missing/biome")) {
            settings.executablePath = executable
            refresh()
            assertNull(associations.schemaFor(config))
        }
    }

    fun testPackageMetadataReplacementClearsAndRestoresAssociation() {
        val schema = createPackage()
        val config = openConfig()
        awaitSchema(schema)
        val metadata = schema.parent.findChild("package.json")!!
        val original = VfsUtil.loadText(metadata)
        WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(metadata, "{\"name\":\"another-package\"}") }
        waitUntil("Replacement package must not retain the old schema") { associations.schemaFor(config) == null }
        refresh()
        assertNull(associations.schemaFor(config))
        WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(metadata, original) }
        awaitSchema(schema)
    }

    fun testCachedCallbackDoesNotReadPackageFiles() {
        val schema = createPackage()
        openConfig()
        awaitSchema(schema)
        val metadata = schema.parent.toNioPath().resolve("package.json")
        val original = Files.readString(metadata)
        try {
            Files.delete(metadata) // Deliberately no VFS refresh/event: a callback must use only its ready snapshot.
            repeat(20) { assertEquals(schema, BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file)) }
        } finally {
            Files.writeString(metadata, original)
        }
    }

    fun testKnownServerVersionMismatchRejectsInstalledSchema() {
        val schema = createPackage()
        val base = myFixture.tempDirFixture.findOrCreateDir(".")
        val executable = settings.executablePath
        val future = ApplicationManager.getApplication().executeOnPooledThread<List<BiomeSchemaAssociationService.Association?>> {
            listOf(associations.installedSchema(executable, base, "2.5.15"),
                associations.installedSchema(executable, base, "2.4.0"),
                associations.installedSchema(executable, base, null))
        }
        waitUntil("Package version checks did not finish") { future.isDone }
        val results = future.get(10, TimeUnit.SECONDS)
        assertEquals(schema, results[0]?.schema)
        assertNull(results[1])
        assertEquals(schema, results[2]?.schema)
    }

    fun testChangedSettingsBeforeQueuedPublicationDiscardTheResult() {
        createPackage()
        val config = openConfig()
        val dispatcher = QueuedDiscoveryDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = BiomeSchemaAssociationService(project, scope)
        Disposer.register(testRootDisposable, service)
        try {
            val job = service.invalidate()
            dispatcher.runPendingReads(scope, expectEdt = true)
            settings.configurationMode = ConfigurationMode.DISABLED
            waitUntil("Queued schema publication did not finish") {
                dispatcher.runNextPendingTask()
                job.isCompleted
            }
            assertNull(service.schemaFor(config))
        } finally {
            scope.cancel()
            dispatcher.drainCancelledTasks()
        }
    }

    fun testSharedExternalConfigurationWithConflictingPackageVersionsIsUnresolved() {
        val rootSchema = createPackage(version = "2.5.15")
        val rootExecutable = settings.executablePath
        val directory = Files.createTempDirectory("biome-schema-shared-")
        Disposer.register(testRootDisposable) { FileUtil.delete(directory.toFile()) }
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, directory.toRealPath().toString())
        val secondBase = directory.resolve("second")
        val pkg = secondBase.resolve("node_modules/@biomejs/biome")
        Files.createDirectories(pkg.resolve("bin"))
        Files.writeString(secondBase.resolve("package.json"), """{"dependencies":{"@biomejs/biome":"2.4.0"}}""")
        Files.writeString(pkg.resolve("package.json"), """{"name":"@biomejs/biome","version":"2.4.0","bin":{"biome":"bin/biome"}}""")
        Files.writeString(pkg.resolve("bin/biome"), "#!/usr/bin/env node\n")
        Files.writeString(pkg.resolve("configuration_schema.json"), schemaText("otherVersionOnly"))
        val base = VfsUtil.findFile(secondBase, true)!!
        ModuleRootModificationUtil.addContentRoot(myFixture.module, base.path)
        waitUntil("Second project base was not published") { base in project.getBaseDirectories() }
        val external = VfsUtil.findFile(Files.writeString(directory.resolve("shared.jsonc"), "{\"\":false}"), true)!!
        settings.configPath = external.path
        settings.executablePath = ""
        myFixture.configureFromExistingVirtualFile(external)
        refresh()
        assertNull("A shared config must not choose one installed version arbitrarily", associations.schemaFor(external))
        settings.executablePath = rootExecutable
        refresh()
        assertEquals(rootSchema, associations.schemaFor(external))
    }

    fun testRestoringTheSameAssociationRefreshesAnalysisAfterPendingLookup() {
        val schema = createPackage()
        openConfig()
        awaitSchema(schema)
        val resets = java.util.concurrent.atomic.AtomicInteger()
        val callback = Runnable { resets.incrementAndGet() }
        json.registerResetAction(callback)
        try {
            val job = associations.invalidate()
            assertNull(BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
            waitUntil("Association was not republished") { job.isCompleted }
            assertEquals(schema, BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
            assertTrue("A pending lookup must not leave schema analysis cached without its association", resets.get() > 0)
        } finally {
            json.unregisterResetAction(callback)
        }
    }

    fun testUnrelatedJsonContentDoesNotClearTheReadyAssociation() {
        val schema = createPackage()
        val unrelated = myFixture.addFileToProject("data/unrelated.json", "{}").virtualFile
        openConfig()
        awaitSchema(schema)
        WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(unrelated, "{\"updated\":true}") }
        assertEquals("Unrelated JSON edits must not interrupt completion", schema,
            BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file))
    }

    fun testPhysicalPackageReplacementUsesTheNewSchemaFile() {
        val oldSchema = createPackage()
        val config = openConfig()
        awaitSchema(oldSchema)
        WriteCommandAction.runWriteCommandAction(project) { oldSchema.parent.delete(this) }
        refresh()
        assertFalse(oldSchema.isValid)
        assertNull(associations.schemaFor(config))
        val replacement = createPackage(key = "newInstallationOnly")
        awaitSchema(replacement)
        assertContainsElements(completions(), "newInstallationOnly")
    }

    fun testMovingConfigurationDropsTheOldPathUntilSettingsFollowIt() {
        val schema = createPackage()
        val config = openConfig()
        awaitSchema(schema)
        WriteCommandAction.runWriteCommandAction(project) { config.rename(this, "moved.json") }
        refresh()
        assertNull(associations.schemaFor(config))
        settings.configPath = config.path
        refresh()
        assertEquals(schema, associations.schemaFor(config))
    }

    fun testWslAliasesShareConfigurationIdentityWhileLinuxPathCaseIsPreserved() {
        assertEquals("//wsl.localhost/ubuntu/home/Config.JSONC",
            schemaConfigurationPath("//wsl$/Ubuntu/home/Config.JSONC"))
        assertEquals("//wsl.localhost/ubuntu/home/Config.JSONC",
            schemaConfigurationPath("//wsl.localhost/UBUNTU/home/Config.JSONC"))
        assertFalse(schemaConfigurationPath("//wsl$/Ubuntu/home/Config.JSONC") ==
            schemaConfigurationPath("//wsl.localhost/ubuntu/home/config.jsonc"))
        assertEquals("//fileserver/share/Config.JSONC", schemaConfigurationPath("//fileserver/share/Config.JSONC"))
    }

    private fun openConfig(path: String = "config/custom.json", text: String = "{\"\":false}"): VirtualFile {
        val config = myFixture.addFileToProject(path, text).virtualFile
        settings.configPath = config.path
        myFixture.configureFromExistingVirtualFile(config)
        return config
    }

    private fun awaitSchema(schema: VirtualFile) = waitUntil("Custom configuration has no expected package schema") {
        BiomeCustomConfigSchemaProvider().getSchemaFile(myFixture.file) == schema
    }

    private fun refresh() {
        val job = associations.invalidate()
        waitUntil("Schema refresh did not complete") { job.isCompleted }
        assertFalse("Refresh was unexpectedly cancelled", job.isCancelled)
    }

    private fun waitUntil(message: String, condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching(message, condition, 10)

    private fun setText(text: String) {
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText(text) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun assertBooleanDiagnostic() {
        assertTrue("Installed schema must validate the custom file", myFixture.doHighlighting().any {
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

    private fun createPackage(directory: String = "", key: String = "selectedOnly", version: String = "2.5.15"): VirtualFile {
        val prefix = directory.takeIf { it.isNotBlank() }?.let { "$it/" } ?: ""
        myFixture.addFileToProject("${prefix}package.json", """{"dependencies":{"@biomejs/biome":"$version"}}""")
        myFixture.addFileToProject("${prefix}node_modules/@biomejs/biome/package.json", """
            {"name":"@biomejs/biome","version":"$version","bin":{"biome":"bin/biome"}}
        """.trimIndent())
        val launcher = myFixture.addFileToProject("${prefix}node_modules/@biomejs/biome/bin/biome", "#!/usr/bin/env node\n").virtualFile
        settings.executablePath = launcher.path
        return myFixture.addFileToProject("${prefix}node_modules/@biomejs/biome/configuration_schema.json", schemaText(key)).virtualFile
    }
}
