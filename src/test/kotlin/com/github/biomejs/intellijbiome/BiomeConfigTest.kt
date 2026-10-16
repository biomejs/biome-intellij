package com.github.biomejs.intellijbiome

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.LightVirtualFile
import junit.framework.TestCase
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class BiomeConfigTest : TestCase() {
    fun testValidJsonClosesStreamOnce() {
        val stream = ConfigStream("""{"root":true,"extends":["base.json"],"unknown":{}}""")
        val config = BiomeConfig.loadFromFile(configFile { stream })!!
        assertEquals(BiomeConfig(true, listOf("base.json")), config)
        assertTrue(config.isRootConfig())
        assertEquals(1, stream.closeCount)
    }

    fun testValidJsoncClosesStreamOnce() {
        val stream = ConfigStream("""{/* comment */ "root":false, "extends":["base.json"],} // end""")
        val config = BiomeConfig.loadFromFile(configFile("biome.jsonc") { stream })!!
        assertEquals(BiomeConfig(false, listOf("base.json")), config)
        assertFalse(config.isRootConfig())
        assertEquals(1, stream.closeCount)
    }

    fun testRootAndExtendsSemanticsArePreserved() {
        for ((json, root) in listOf(
            "{}" to true,
            """{"root":null,"extends":null}""" to true,
            """{"root":true}""" to true,
            """{"root":false}""" to false,
            """{"root":"false"}""" to false,
            """{"extends":"//"}""" to false,
            """{"root":true,"extends":["base.json","//"]}""" to false,
            """{"extends":["base.json"]}""" to true,
        )) {
            val stream = ConfigStream(json)
            assertEquals(json, root, BiomeConfig.loadFromFile(configFile { stream })!!.isRootConfig())
            assertEquals(1, stream.closeCount)
        }
    }

    fun testMalformedInputReturnsNullAndClosesStreamOnce() {
        for (json in listOf("{", "", "[]", """{"root":{}}""", """{"extends":{}}""")) {
            val stream = ConfigStream(json)
            assertNull(json, BiomeConfig.loadFromFile(configFile { stream }))
            assertEquals(1, stream.closeCount)
        }
    }

    fun testTruncatedBooleanReturnsNullAndClosesStreamOnce() {
        val stream = ConfigStream("""{"root":""")
        assertNull(BiomeConfig.loadFromFile(configFile("biome.jsonc") { stream }))
        assertEquals(1, stream.closeCount)
    }

    fun testIoFailureOpeningReturnsNull() {
        assertNull(BiomeConfig.loadFromFile(configFile { throw IOException("open") }))
    }

    fun testIoFailureReadingReturnsNullAndClosesStreamOnce() {
        val stream = ConfigStream("{}", readFailure = IOException("read"))
        assertNull(BiomeConfig.loadFromFile(configFile { stream }))
        assertEquals(1, stream.closeCount)
    }

    fun testIoFailureClosingReturnsNullAndClosesStreamOnce() {
        val stream = ConfigStream("{}", closeFailure = IOException("close"))
        assertNull(BiomeConfig.loadFromFile(configFile { stream }))
        assertEquals(1, stream.closeCount)
    }

    fun testCoroutineCancellationIdentityIsPreserved() = assertFailureAtEveryStage(CancellationException("cancel"))

    fun testPlatformCancellationIdentityIsPreserved() = assertFailureAtEveryStage(ProcessCanceledException())

    fun testPlatformControlFlowIdentityIsPreserved() = assertFailureAtEveryStage(TestControlFlowException())

    fun testFatalFailureIdentityIsPreserved() = assertFailureAtEveryStage(LinkageError("controlled fatal failure"))

    fun testUnexpectedRuntimeFailureIdentityIsPreserved() = assertFailureAtEveryStage(IllegalStateException("unexpected"))

    fun testClosingCancellationAfterExpectedReadFailureIsPreserved() {
        for (cancellation in listOf(CancellationException("cancel close"), ProcessCanceledException(), TestControlFlowException())) {
            assertCloseFailureAfterExpectedReadFailure(cancellation)
        }
    }

    fun testClosingFatalFailureAfterExpectedReadFailureIsPreserved() =
        assertCloseFailureAfterExpectedReadFailure(LinkageError("controlled close failure"))

    private fun assertCloseFailureAfterExpectedReadFailure(failure: Throwable) {
        for (stream in listOf(
            ConfigStream("{", closeFailure = failure),
            ConfigStream("{}", readFailure = IOException("read"), closeFailure = failure),
        )) {
            assertSame(failure, thrown { BiomeConfig.loadFromFile(configFile { stream }) })
            assertEquals(1, stream.closeCount)
        }
    }

    private fun assertFailureAtEveryStage(failure: Throwable) {
        assertSame(failure, thrown { BiomeConfig.loadFromFile(configFile { throw failure }) })
        for (reading in listOf(true, false)) {
            val stream = ConfigStream("{}", readFailure = failure.takeIf { reading }, closeFailure = failure.takeIf { !reading })
            assertSame(failure, thrown { BiomeConfig.loadFromFile(configFile { stream }) })
            assertEquals(1, stream.closeCount)
        }
    }

    private fun thrown(block: () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected the original failure to propagate")
    }

    private fun configFile(name: String = "biome.json", open: () -> InputStream) = object : LightVirtualFile(name) {
        override fun getInputStream(): InputStream = open()
    }

    private class TestControlFlowException : RuntimeException(), ControlFlowException

    private class ConfigStream(
        content: String,
        private val readFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
    ) : ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)) {
        var closeCount = 0
            private set

        override fun read(): Int {
            readFailure?.let { throw it }
            return super.read()
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            readFailure?.let { throw it }
            return super.read(bytes, offset, length)
        }

        override fun close() {
            closeCount++
            super.close()
            closeFailure?.let { throw it }
        }
    }
}
