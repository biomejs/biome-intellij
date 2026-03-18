package com.github.biomejs.intellijbiome.lsp

import com.intellij.lang.javascript.modules.TestNpmPackage

@TestNpmPackage("@biomejs/biome@2.2.3")
class UnusedFunctionHighlightingTest : BiomeLspFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        setUpLspFixture("unused-function")
        myFixture.configureBiomeForLspTests()
    }

    fun testUnusedFunctionDiagnosticsProduceSnapshotDiagnostics() {
        myFixture.checkBiomeHighlightingSnapshot("index.js", "unused-function/index.expected.js")
    }
}
