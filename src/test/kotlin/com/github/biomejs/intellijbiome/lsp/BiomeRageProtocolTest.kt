package com.github.biomejs.intellijbiome.lsp

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionManager
import com.github.biomejs.intellijbiome.ui.BiomeTroubleshootingDialog
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent

class BiomeRageProtocolTest : BasePlatformTestCase() {
    fun testTroubleshootingIsAvailableThroughFindAction() {
        assertNotNull("Troubleshooting must be available without starting a server",
            ActionManager.getInstance().getAction("BiomeCollectTroubleshooting"))
    }

    fun testReleasedResponseRendersMarkupArrayValues() {
        val response = Files.readString(Path.of("src/test/testData/lsp/rage-2.5.15.json"))
        val text = decode(response).toPlainText()
        assertEquals("Server\nVersion: 2.5.15\nName: biome_lsp\nCPU Architecture: aarch64\nOS: macos\nWorkspace\nOpen Documents: 3\n", text)
    }

    fun testParamsAreAnEmptyObject() {
        assertEquals(JsonParser.parseString("{}"), Gson().toJsonTree(BiomeRageParams()))
    }

    fun testMarkupIsLiteralTextWithStylesIgnored() {
        val text = decode("""{"entries":[{"markup":[{"content":"<script>alert(1)</script>","elements":["Emphasis"]},{"content":" then text","elements":[]}]},{"pair":{"name":"Key","value":[{"content":"first","elements":[]},{"content":"second","elements":[]}]}}]}""").toPlainText()
        assertEquals("<script>alert(1)</script> then text\nKey: firstsecond\n", text)
    }

    fun testUnknownMalformedAndAmbiguousEntriesAreOmittedWithoutLeakingPayloads() {
        val entries = """{"entries":[null,1,{"future":"secret"},{"section":42},{"pair":{"name":"secret","value":"wrong"}},{"markup":[{"content":true}]},{"section":"secret","markup":[]},{"section":"Valid"}]}"""
        val text = decode(entries).toPlainText()
        assertTrue(text, text.contains("Valid\n"))
        assertTrue(text, text.contains("omitted"))
        assertFalse(text, text.contains("secret"))
        assertFalse(text, text.contains("true"))
    }

    fun testMissingAndWrongEntriesProduceAnExplanation() {
        for (json in listOf("{}", "{\"entries\":null}", "{\"entries\":42}")) {
            assertTrue(json, decode(json).toPlainText().contains("unavailable"))
        }
    }

    fun testEmptyResponseIsRecognized() {
        assertTrue(decode("{\"entries\":[]}").toPlainText().contains("No entries"))
    }

    fun testLargeContentIsTruncatedBeforeBuildingPreview() {
        val result = decode(Gson().toJson(mapOf("entries" to listOf(mapOf("section" to "x".repeat(200_000))))))
        val text = result.toPlainText()
        assertTrue(text.length.toString(), text.length <= 65_536)
        assertTrue(text.takeLast(100), text.contains("truncated"))
    }

    fun testEntryAndMarkupNodeLimitsAreVisible() {
        val entries = List(300) { mapOf("section" to "entry") } + mapOf("section" to "MUST_NOT_RENDER")
        val text = decode(Gson().toJson(mapOf("entries" to entries))).toPlainText()
        assertTrue(text, text.contains("truncated"))
        assertFalse(text.contains("MUST_NOT_RENDER"))
        val nodes = List(5_000) { mapOf("content" to "", "elements" to emptyList<String>()) }
        assertTrue(decode(Gson().toJson(mapOf("entries" to listOf(mapOf("markup" to nodes))))).toPlainText().contains("truncated"))
    }

    fun testControlCharactersDoNotReachPreview() {
        val text = decode(Gson().toJson(mapOf("entries" to listOf(mapOf("section" to "a\u0000b\u001Bc\r\nd"))))).toPlainText()
        assertEquals("abc\nd\n", text)
    }

    fun testPreviewIsReadOnlyAndCopiesOnlyOnExplicitAction() {
        val clipboard = CopyPasteManager.getInstance()
        val previous = clipboard.contents
        clipboard.setContents(StringSelection("unchanged clipboard"))
        val text = "<html>literal server text</html>\nsecond line"
        val dialog = BiomeTroubleshootingDialog(project, text)
        try {
            assertFalse("The preview must be read-only", dialog.preview.isEditable)
            assertEquals(text, dialog.preview.text)
            assertEquals("unchanged clipboard", clipboard.getContents(DataFlavor.stringFlavor))
            dialog.copyAction.actionPerformed(ActionEvent(dialog, 0, "Copy"))
            assertEquals(text, clipboard.getContents(DataFlavor.stringFlavor))
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            clipboard.setContents(previous ?: StringSelection(""))
        }
    }

    private fun decode(json: String): BiomeRageResult = Gson().fromJson(json, BiomeRageResult::class.java)
}
