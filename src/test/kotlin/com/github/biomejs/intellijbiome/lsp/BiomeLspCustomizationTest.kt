package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspOnTypeFormattingDisabled
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import java.nio.file.Path

class BiomeLspCustomizationTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private lateinit var file: VirtualFile
    private lateinit var descriptor: LspServerDescriptor
    private val settings get() = BiomeSettings.getInstance(project)

    override fun setUp() {
        super.setUp()
        file = myFixture.addFileToProject("customization.js", "const value=1;").virtualFile
        settings.apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            configPath = file.parent.path
        }
        var captured: LspServerDescriptor? = null
        BiomeLspServerSupportProvider().fileOpened(project, file, object : LspServerSupportProvider.LspServerStarter {
            override fun ensureServerStarted(descriptor: LspServerDescriptor) {
                captured = descriptor
            }
        })
        descriptor = checkNotNull(captured) { "The public provider did not supply a descriptor" }
    }

    fun testCompletionAndDefinitionStayDisabled() {
        assertSame(LspCompletionDisabled, descriptor.lspCustomization.completionCustomizer)
        assertSame(LspGoToDefinitionDisabled, descriptor.lspCustomization.goToDefinitionCustomizer)
    }

    fun testOnTypeFormattingRemainsDisabled() {
        assertSame(LspOnTypeFormattingDisabled, descriptor.lspCustomization.onTypeFormattingCustomizer)
    }

    fun testFormattingOwnershipTracksSettingsForEveryIdeAndServerOwnershipCombination() {
        val formatting = descriptor.lspCustomization.formattingCustomizer as LspFormattingSupport
        for (enabled in listOf(false, true, false)) {
            settings.enableLspFormat = enabled
            for (ideCanFormat in listOf(false, true)) {
                for (serverWantsToFormat in listOf(false, true)) {
                    assertEquals("enabled=$enabled, IDE=$ideCanFormat, server=$serverWantsToFormat", enabled,
                        formatting.shouldFormatThisFileExclusivelyByServer(file, ideCanFormat, serverWantsToFormat))
                }
            }
        }
    }

    fun testDisabledPluginDoesNotClaimFormatting() {
        val formatting = descriptor.lspCustomization.formattingCustomizer as LspFormattingSupport
        settings.enableLspFormat = true
        settings.configurationMode = ConfigurationMode.DISABLED
        assertFalse(formatting.shouldFormatThisFileExclusivelyByServer(file, false, true))
        assertFalse(formatting.shouldFormatThisFileExclusivelyByServer(file, true, false))
    }

    fun testCodeActionsKeepImmediateEditsAndWorkspaceConfigurationCapabilities() {
        val actions = descriptor.lspCustomization.codeActionsCustomizer as LspCodeActionsSupport
        assertTrue(actions.quickFixesSupport)
        assertTrue(actions.intentionActionsSupport)
        val capabilities = descriptor.clientCapabilities
        assertEquals(true, capabilities.workspace.configuration)
        val codeAction = capabilities.textDocument.codeAction
        assertNull(codeAction.resolveSupport)
        assertEquals(true, codeAction.disabledSupport)
        assertEquals(true, codeAction.dataSupport)
        assertEquals(listOf("quickfix", "", "source", "refactor"),
            codeAction.codeActionLiteralSupport.codeActionKind.valueSet)
    }
}
