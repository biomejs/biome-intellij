package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExpectedHighlightingData
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import org.junit.Assume.assumeTrue

private const val BIOME_TEST_EXECUTABLE_PROPERTY = "biome.test.executable"
private const val BIOME_TEST_EXECUTABLE_ENV = "BIOME_TEST_EXECUTABLE"
private const val BIOME_LSP_DESCRIPTOR_CLASS_NAME = "com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor"

fun CodeInsightTestFixture.configureBiomeForLspTests(configPath: String = "") {
    val executable = findBiomeTestExecutable(this)
    assumeTrue(
        "Biome test executable not found in the temp project. Ensure the fixture package install succeeded, or set -D$BIOME_TEST_EXECUTABLE_PROPERTY / $BIOME_TEST_EXECUTABLE_ENV.",
        executable != null
    )

    val settings = BiomeSettings.getInstance(project)
    settings.configurationMode = ConfigurationMode.MANUAL
    settings.configPath = configPath
    settings.executablePath = executable!!
}

fun CodeInsightTestFixture.checkBiomeHighlightingSnapshot(filePath: String, expectedFilePath: String) {
    val expectedSnapshot = Path.of(testDataPath, expectedFilePath).toFile().readText().replace("\r\n", "\n")
    val file = findFileInTempDir(filePath)
        ?: error("Could not find test file in temp project: $filePath")
    val expectedHighlightingData = ExpectedHighlightingData(
        EditorFactory.getInstance().createDocument(expectedSnapshot),
        true,
        false,
        false,
        true
    ).apply {
        init()
    }

    configureFromExistingVirtualFile(file)
    waitUntilFileOpenedByLspServer(project, file, BIOME_LSP_DESCRIPTOR_CLASS_NAME)

    // Biome sometimes misses the first diagnostics push in this harness, so fall back to
    // reopening the file and then forcing a minimal didChange if the initial check still times out.
    runCatching {
        checkLspHighlightingForData(
            expectedHighlightingData,
            descriptorClassName = BIOME_LSP_DESCRIPTOR_CLASS_NAME,
            initialTimeout = 10,
        )
    }.recoverCatching {
        reopenFileAfterServerSetup(file)
        waitUntilFileOpenedByLspServer(project, file, BIOME_LSP_DESCRIPTOR_CLASS_NAME)
        checkLspHighlightingForData(
            expectedHighlightingData,
            descriptorClassName = BIOME_LSP_DESCRIPTOR_CLASS_NAME,
            initialTimeout = 10,
        )
    }.recoverCatching {
        triggerLspReanalysis()
        checkLspHighlightingForData(
            expectedHighlightingData,
            descriptorClassName = BIOME_LSP_DESCRIPTOR_CLASS_NAME,
            initialTimeout = 10,
        )
    }.getOrThrow()
}

private fun CodeInsightTestFixture.reopenFileAfterServerSetup(file: VirtualFile) {
    FileEditorManager.getInstance(project).closeFile(file)
    configureFromExistingVirtualFile(file)
}

private fun CodeInsightTestFixture.triggerLspReanalysis() {
    val document = editor.document
    val originalLength = document.textLength
    val fileDocumentManager = FileDocumentManager.getInstance()

    WriteCommandAction.runWriteCommandAction(project) {
        document.insertString(originalLength, " ")
        fileDocumentManager.saveDocument(document)
    }

    WriteCommandAction.runWriteCommandAction(project) {
        document.deleteString(originalLength, originalLength + 1)
        fileDocumentManager.saveDocument(document)
    }

    fileDocumentManager.saveAllDocuments()
}

private fun findBiomeTestExecutable(fixture: CodeInsightTestFixture): String? {
    val configuredPath = System.getProperty(BIOME_TEST_EXECUTABLE_PROPERTY)
        ?: System.getenv(BIOME_TEST_EXECUTABLE_ENV)
    if (!configuredPath.isNullOrBlank() && Files.isRegularFile(Path.of(configuredPath))) {
        return configuredPath
    }

    val projectBasePath = fixture.tempDirPath
        ?.let(::File)
        ?.takeIf(File::exists)
        ?.toPath()
    val candidateNames = buildList {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            add("biome.cmd")
            add("biome.exe")
        }
        add("biome")
    }

    return candidateNames
        .asSequence()
        .mapNotNull { name -> projectBasePath?.resolve("node_modules/.bin/$name") }
        .firstOrNull { Files.isRegularFile(it) }
        ?.absolutePathString()
}
