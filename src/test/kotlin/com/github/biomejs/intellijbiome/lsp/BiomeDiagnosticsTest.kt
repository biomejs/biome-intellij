package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspOnTypeFormattingDisabled
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import org.eclipse.lsp4j.Diagnostic
import java.nio.file.Path

class BiomeDiagnosticsTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private val descriptor by lazy {
        val file = myFixture.addFileToProject("diagnostic.js", "").virtualFile
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            configPath = file.parent.path
        }
        var capturedDescriptor: LspClientDescriptor? = null
        BiomeLspServerSupportProvider().fileOpened(project, file, object : LspIntegrationProvider.LspClientStarter {
            override fun ensureClientStarted(descriptor: LspClientDescriptor) {
                capturedDescriptor = descriptor
            }
        })
        checkNotNull(capturedDescriptor) { "The public provider did not supply a descriptor" }
    }

    fun testDisabledFeaturesRemainDisabled() {
        val customization = descriptor.lspCustomization
        assertSame(LspGoToDefinitionDisabled, customization.goToDefinitionCustomizer)
        assertSame(LspCompletionDisabled, customization.completionCustomizer)
        assertSame(LspOnTypeFormattingDisabled, customization.onTypeFormattingCustomizer)
    }

    fun testFormattingUsesCurrentSettings() {
        val formatting = descriptor.lspCustomization.formattingCustomizer as LspFormattingSupport
        val file = myFixture.findFileInTempDir("diagnostic.js")!!
        val settings = BiomeSettings.getInstance(project)
        settings.enableLspFormat = false
        assertFalse(formatting.shouldFormatThisFileExclusivelyByServer(file, true, true))
        settings.enableLspFormat = true
        assertTrue(formatting.shouldFormatThisFileExclusivelyByServer(file, true, false))
        settings.configurationMode = ConfigurationMode.DISABLED
        assertFalse(formatting.shouldFormatThisFileExclusivelyByServer(file, true, true))
    }

    fun testAbsentCodeRendersWithoutSuffix() {
        assertRendering(Diagnostic().apply { setMessage("sample diagnostic") }, "Biome: sample diagnostic")
    }

    fun testStringCodeRendersInMessageAndTooltip() {
        val diagnostic = Diagnostic().apply {
            setMessage("sample diagnostic")
            setCode("lint/style/example")
        }
        assertRendering(diagnostic, "Biome: sample diagnostic (lint/style/example)")
    }

    fun testIntegerAndZeroCodesRenderInMessageAndTooltip() {
        for (code in listOf(123, 0)) {
            val diagnostic = Diagnostic().apply { setMessage("sample diagnostic"); setCode(code) }
            assertRendering(diagnostic, "Biome: sample diagnostic ($code)")
        }
    }

    fun testTooltipEscapesMessageAndCodeWithoutChangingPlainMessage() {
        val diagnostic = Diagnostic().apply {
            setMessage("Use <value> & \"quoted\"\nnext line")
            setCode("rule/<tag>&")
        }
        assertEquals("Biome: Use <value> & \"quoted\"\nnext line (rule/<tag>&)", support().getMessage(diagnostic))
        assertEquals("Biome: Use &lt;value&gt; &amp; &quot;quoted&quot;<br>next line (rule/&lt;tag&gt;&amp;)",
            support().getTooltip(diagnostic))
    }

    fun testTooltipNormalizesAllLineEndings() {
        val diagnostic = Diagnostic().apply { setMessage("first\r\nsecond\rthird\nfourth"); setCode("rule") }
        assertEquals("Biome: first\r\nsecond\rthird\nfourth (rule)", support().getMessage(diagnostic))
        assertEquals("Biome: first<br>second<br>third<br>fourth (rule)", support().getTooltip(diagnostic))
    }

    private fun support(): LspDiagnosticsSupport = descriptor.lspCustomization.diagnosticsCustomizer as LspDiagnosticsSupport

    private fun assertRendering(diagnostic: Diagnostic, expected: String) {
        assertEquals(expected, support().getMessage(diagnostic))
        assertEquals(expected, support().getTooltip(diagnostic))
    }
}
