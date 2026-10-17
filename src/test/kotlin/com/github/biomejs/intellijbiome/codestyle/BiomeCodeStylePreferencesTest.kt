package com.github.biomejs.intellijbiome.codestyle

import com.github.biomejs.intellijbiome.ProcessCommandParameter
import com.intellij.lang.javascript.JavaScriptSupportLoader
import com.intellij.lang.javascript.formatter.JSCodeStyleSettings
import com.intellij.lang.typescript.formatter.TypeScriptCodeStyleSettings
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class BiomeCodeStylePreferencesTest : BasePlatformTestCase() {
    fun testDecodesOnlyObservableDefaults() {
        assertEquals(BiomeCodeStylePreferences(true, null, true, true),
            decodeBiomeCodeStyle("function f() {\n\treturn \"x\";\n}\n"))
    }

    fun testDecodesSpaceIndentQuotesSemicolonsAndAllLineEndings() {
        for (separator in listOf("\n", "\r\n", "\r")) {
            val output = listOf("function f() {", "    return 'x'", "}").joinToString(separator)
            assertEquals(BiomeCodeStylePreferences(false, 4, false, false), decodeBiomeCodeStyle(output))
        }
    }

    fun testDecodesZeroSpaceIndentWidth() {
        assertEquals(BiomeCodeStylePreferences(false, 0, true, true),
            decodeBiomeCodeStyle("function f() {\nreturn \"x\";\n}\n"))
    }

    fun testDecodesMaximumSpaceIndentWidth() {
        assertEquals(BiomeCodeStylePreferences(false, 24, true, true),
            decodeBiomeCodeStyle("function f() {\n${" ".repeat(24)}return \"x\";\n}\n"))
    }

    fun testRejectsIgnoredMalformedOrUnexpectedOutput() {
        for (output in listOf(BIOME_CODE_STYLE_PROBE, "", "function f() {\n \treturn 'x';\n}\n",
            "function f() {\n${" ".repeat(25)}return 'x';\n}\n", "function f() {\n\treturn 'x\";\n}\n",
            "warning\nfunction f() {\n\treturn 'x';\n}\n", "function f() {\n\treturn 'x';\n}\nextra")) {
            assertNull("Unrecognized output must not change project preferences", decodeBiomeCodeStyle(output))
        }
    }

    fun testManualConfigArgumentsKeepRelativeAndHostAbsolutePathsDistinct() {
        val relative = "config with spaces/biome.json"
        assertEquals(relative, (biomeCodeStyleConfigArgument(relative) as ProcessCommandParameter.Value).value)
        val absolute = Path.of(System.getProperty("java.io.tmpdir"), "not-yet-created-biome-config.json").toAbsolutePath()
        assertEquals(absolute, (biomeCodeStyleConfigArgument(absolute.toString()) as ProcessCommandParameter.FilePath).path)
        val targetNative = biomeCodeStyleConfigArgument("/home/project/biome.json")
        if (SystemInfoRt.isWindows) {
            assertEquals("/home/project/biome.json", (targetNative as ProcessCommandParameter.Value).value)
        } else {
            assertEquals(Path.of("/home/project/biome.json"), (targetNative as ProcessCommandParameter.FilePath).path)
        }
    }

    fun testImportsOnlySelectedLanguageIntoProjectClone() {
        val manager = CodeStyleSettingsManager.getInstance(project)
        val original = manager.currentSettings
        val previousProject = manager.mainProjectCodeStyle
        val previouslySelected = manager.USE_PER_PROJECT_SETTINGS
        val originalState = original.clone()
        try {
            val imported = applyBiomeCodeStyle(project, BiomeCodeStyleLanguage.TypeScript,
                BiomeCodeStylePreferences(false, 7, false, false))
            assertNotSame(original, imported)
            assertSame(imported, manager.mainProjectCodeStyle)
            assertTrue(manager.USE_PER_PROJECT_SETTINGS)
            val typeScript = imported.getCustomSettings(TypeScriptCodeStyleSettings::class.java)
            assertFalse(typeScript.USE_DOUBLE_QUOTES)
            assertFalse(typeScript.USE_SEMICOLON_AFTER_STATEMENT)
            val originalTypeScript = originalState.getCustomSettings(TypeScriptCodeStyleSettings::class.java)
            assertEquals(originalTypeScript.FORCE_QUOTE_STYlE, typeScript.FORCE_QUOTE_STYlE)
            assertEquals(originalTypeScript.FORCE_SEMICOLON_STYLE, typeScript.FORCE_SEMICOLON_STYLE)
            val indent = imported.getCommonSettings(JavaScriptSupportLoader.TYPESCRIPT).indentOptions!!
            assertFalse(indent.USE_TAB_CHARACTER)
            assertEquals(7, indent.INDENT_SIZE)
            val oldIndent = originalState.getCommonSettings(JavaScriptSupportLoader.TYPESCRIPT).indentOptions!!
            assertEquals(oldIndent.TAB_SIZE, indent.TAB_SIZE)
            assertEquals(oldIndent.CONTINUATION_INDENT_SIZE, indent.CONTINUATION_INDENT_SIZE)
            assertEquals(originalState.getCommonSettings(JavaScriptSupportLoader.TYPESCRIPT).RIGHT_MARGIN,
                imported.getCommonSettings(JavaScriptSupportLoader.TYPESCRIPT).RIGHT_MARGIN)
            assertEquals(originalState.LINE_SEPARATOR, imported.LINE_SEPARATOR)
            assertEquals(originalState.getCustomSettings(JSCodeStyleSettings::class.java),
                imported.getCustomSettings(JSCodeStyleSettings::class.java))
            assertEquals(originalState.getCommonSettings(JavaScriptSupportLoader.JAVASCRIPT.language),
                imported.getCommonSettings(JavaScriptSupportLoader.JAVASCRIPT.language))
            assertEquals("The active source scheme must remain unchanged", originalState, original)
        } finally {
            manager.setMainProjectCodeStyle(previousProject)
            manager.USE_PER_PROJECT_SETTINGS = previouslySelected
            manager.notifyCodeStyleSettingsChanged()
        }
    }

    fun testTabImportRetainsIndentAndTabWidths() {
        val manager = CodeStyleSettingsManager.getInstance(project)
        val previousProject = manager.mainProjectCodeStyle
        val previouslySelected = manager.USE_PER_PROJECT_SETTINGS
        val originalIndent = manager.currentSettings.getCommonSettings(JavaScriptSupportLoader.JAVASCRIPT.language).indentOptions!!
        val originalSize = originalIndent.INDENT_SIZE
        val originalTabSize = originalIndent.TAB_SIZE
        try {
            val imported = applyBiomeCodeStyle(project, BiomeCodeStyleLanguage.JavaScript,
                BiomeCodeStylePreferences(true, null, true, true))
            val indent = imported.getCommonSettings(JavaScriptSupportLoader.JAVASCRIPT.language).indentOptions!!
            assertTrue(indent.USE_TAB_CHARACTER)
            assertEquals(originalSize, indent.INDENT_SIZE)
            assertEquals(originalTabSize, indent.TAB_SIZE)
        } finally {
            manager.setMainProjectCodeStyle(previousProject)
            manager.USE_PER_PROJECT_SETTINGS = previouslySelected
            manager.notifyCodeStyleSettingsChanged()
        }
    }
}
