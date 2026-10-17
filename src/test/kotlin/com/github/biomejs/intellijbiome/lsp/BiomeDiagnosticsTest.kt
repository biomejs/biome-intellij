package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticCodeDescription
import java.nio.file.Path

class BiomeDiagnosticsTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private val renderer by lazy {
        val file = myFixture.addFileToProject("diagnostic.js", "").virtualFile
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            configPath = file.parent.path
        }
        var capturedDescriptor: LspServerDescriptor? = null
        BiomeLspServerSupportProvider().fileOpened(project, file, object : LspServerSupportProvider.LspServerStarter {
            override fun ensureServerStarted(descriptor: LspServerDescriptor) {
                capturedDescriptor = descriptor
            }
        })
        checkNotNull(capturedDescriptor) { "The public provider did not supply a descriptor" }.lspDiagnosticsSupport
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

    fun testRuleCodeLinksToHttpAndHttpsDocumentation() {
        for (href in listOf("https://biomejs.dev/linter/rules/example/", "http://localhost:8080/rules/example", "HTTPS://biomejs.dev/rules/example")) {
            val diagnostic = Diagnostic().apply {
                setMessage("sample diagnostic")
                setCode("lint/style/example")
                codeDescription = DiagnosticCodeDescription(href)
            }
            assertEquals("Biome: sample diagnostic (lint/style/example)", support().getMessage(diagnostic))
            assertEquals("Biome: sample diagnostic (<a href=\"$href\">lint/style/example</a>)",
                support().getTooltip(diagnostic))
        }
    }

    fun testNumericRuleCodesLinkToDocumentation() {
        for (code in listOf(123, 0)) {
            val diagnostic = Diagnostic().apply {
                setMessage("sample diagnostic")
                setCode(code)
                codeDescription = DiagnosticCodeDescription("https://biomejs.dev/rules/example")
            }
            assertEquals("Biome: sample diagnostic ($code)", support().getMessage(diagnostic))
            assertEquals("Biome: sample diagnostic (<a href=\"https://biomejs.dev/rules/example\">$code</a>)",
                support().getTooltip(diagnostic))
        }
    }

    fun testLinkedTooltipEscapesMessageCodeAndHref() {
        val diagnostic = Diagnostic().apply {
            setMessage("Use <value> & \"quoted\"")
            setCode("rule/<tag>&\"code\"")
            codeDescription = DiagnosticCodeDescription("https://biomejs.dev/rules/example?version=2&view=details")
        }
        assertEquals("Biome: Use <value> & \"quoted\" (rule/<tag>&\"code\")", support().getMessage(diagnostic))
        assertEquals("Biome: Use &lt;value&gt; &amp; &quot;quoted&quot; (<a href=\"https://biomejs.dev/rules/example?version=2&amp;view=details\">rule/&lt;tag&gt;&amp;&quot;code&quot;</a>)",
            support().getTooltip(diagnostic))
    }

    fun testLinkedTooltipNormalizesMessageAndCodeLineEndings() {
        val diagnostic = Diagnostic().apply {
            setMessage("first\r\nsecond\rthird\nfourth")
            setCode("rule\r\nname")
            codeDescription = DiagnosticCodeDescription("https://biomejs.dev/rules/example")
        }
        assertEquals("Biome: first\r\nsecond\rthird\nfourth (rule\r\nname)", support().getMessage(diagnostic))
        assertEquals("Biome: first<br>second<br>third<br>fourth (<a href=\"https://biomejs.dev/rules/example\">rule<br>name</a>)",
            support().getTooltip(diagnostic))
    }

    fun testDocumentationLinkWithoutCodeDoesNotAddSuffix() {
        val diagnostic = Diagnostic().apply {
            setMessage("sample diagnostic")
            codeDescription = DiagnosticCodeDescription("https://biomejs.dev/rules/example")
        }
        assertRendering(diagnostic, "Biome: sample diagnostic")
    }

    fun testUnsafeDocumentationLinksKeepEscapedPlainCode() {
        val unsafeLinks = listOf(
            "", "javascript:alert(1)", "data:text/html,test", "file:///tmp/rule.html",
            "javascript://biomejs.dev/rules/example", "file://biomejs.dev/rules/example",
            "/rules/example", "//biomejs.dev/rules/example", "https:rules/example", "https:///rules/example",
            "https://user:password@biomejs.dev/rules/example", "https://biomejs.dev@evil.example/rules/example",
            "https://biomejs.dev/rules/\" onclick=\"alert(1)", "https://biomejs.dev/rules/<script>",
            "https://biomejs.dev/rules/\u0000example", "https://biomejs.dev/rules/\texample",
            "https://biomejs.dev/rules/\nexample", "https://biomejs.dev/rules/\rexample",
            "https://biomejs.dev/rules/\u007fexample", "https://biomejs.dev/rules/\u0085example",
        )
        for (href in unsafeLinks) {
            val diagnostic = Diagnostic().apply {
                setMessage("Use <value>")
                setCode("rule/<tag>&")
                codeDescription = DiagnosticCodeDescription(href)
            }
            assertEquals("Biome: Use <value> (rule/<tag>&)", support().getMessage(diagnostic))
            assertEquals("Unexpected link for $href", "Biome: Use &lt;value&gt; (rule/&lt;tag&gt;&amp;)",
                support().getTooltip(diagnostic))
        }
    }

    private fun support(): LspDiagnosticsSupport = checkNotNull(renderer)

    private fun assertRendering(diagnostic: Diagnostic, expected: String) {
        assertEquals(expected, support().getMessage(diagnostic))
        assertEquals(expected, support().getTooltip(diagnostic))
    }
}
