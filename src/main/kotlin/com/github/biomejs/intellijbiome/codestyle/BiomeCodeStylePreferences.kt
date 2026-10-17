package com.github.biomejs.intellijbiome.codestyle

import com.intellij.lang.Language
import com.intellij.lang.javascript.JavaScriptSupportLoader
import com.intellij.lang.javascript.formatter.JSCodeStyleSettings
import com.intellij.lang.typescript.formatter.TypeScriptCodeStyleSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager

internal data class BiomeCodeStylePreferences(
    val useTabs: Boolean,
    val spaceIndentWidth: Int?,
    val useDoubleQuotes: Boolean,
    val useSemicolons: Boolean,
)

internal enum class BiomeCodeStyleLanguage(
    val displayName: String,
    val language: Language,
    val settingsClass: Class<out JSCodeStyleSettings>,
) {
    JavaScript("JavaScript", JavaScriptSupportLoader.JAVASCRIPT.language, JSCodeStyleSettings::class.java),
    TypeScript("TypeScript", JavaScriptSupportLoader.TYPESCRIPT, TypeScriptCodeStyleSettings::class.java);

    companion object {
        fun forFile(file: VirtualFile): BiomeCodeStyleLanguage? = when (file.extension) {
            "js", "jsx", "mjs", "cjs" -> JavaScript
            "ts", "tsx", "mts", "cts" -> TypeScript
            else -> null
        }
    }
}

// The input is deliberately unformatted, so ignored/disabled formatter output cannot be imported.
internal const val BIOME_CODE_STYLE_PROBE = "function f( ){return \"x\";}\n"

/** Read only preferences observable from this fixed, valid JavaScript/TypeScript sample. */
internal fun decodeBiomeCodeStyle(output: String): BiomeCodeStylePreferences? {
    val normalized = output.replace("\r\n", "\n").replace('\r', '\n')
    val match = formattedProbe.matchEntire(normalized) ?: return null
    val indentation = match.groupValues[1]
    val useTabs = indentation == "\t"
    if (!useTabs && (indentation.length !in 0..24 || indentation.any { it != ' ' })) return null
    return BiomeCodeStylePreferences(
        useTabs = useTabs,
        spaceIndentWidth = if (useTabs) null else indentation.length,
        useDoubleQuotes = match.groupValues[2] == "\"",
        useSemicolons = match.groupValues[3] == ";",
    )
}

private val formattedProbe = Regex("""\Afunction f\(\) \{\n([\t ]*)return (["'])x\2(;?)\n\}\n?\z""")

/** Copy the active scheme before importing into the selected language's project settings. */
internal fun applyBiomeCodeStyle(
    project: Project,
    language: BiomeCodeStyleLanguage,
    preferences: BiomeCodeStylePreferences,
): CodeStyleSettings {
    val manager = CodeStyleSettingsManager.getInstance(project)
    val imported = manager.cloneSettings(manager.currentSettings)
    val common = imported.getCommonSettings(language.language)
    val indent = common.indentOptions ?: common.initIndentOptions()
    indent.USE_TAB_CHARACTER = preferences.useTabs
    preferences.spaceIndentWidth?.let { indent.INDENT_SIZE = it }
    imported.getCustomSettings(language.settingsClass).apply {
        USE_DOUBLE_QUOTES = preferences.useDoubleQuotes
        USE_SEMICOLON_AFTER_STATEMENT = preferences.useSemicolons
    }
    // Tab width, continuation indentation, margins, line endings and enforcement flags
    // are not observable from the sample and retain the user's existing values.
    manager.setMainProjectCodeStyle(imported)
    manager.USE_PER_PROJECT_SETTINGS = true
    manager.notifyCodeStyleSettingsChanged()
    return imported
}
