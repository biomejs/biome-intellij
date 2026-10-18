package com.github.biomejs.intellijbiome.lsp

import com.google.gson.JsonParser
import junit.framework.TestCase
import org.eclipse.lsp4j.DocumentOnTypeFormattingOptions
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams

class BiomeOnTypeRegistrationTest : TestCase() {
    private val registrations = BiomeOnTypeFormattingRegistration()

    fun testReleasedDynamicTriggersAndArbitraryRegistrationId() {
        register("server-selected-id", """{"documentSelector":null,"firstTriggerCharacter":"}","moreTriggerCharacter":["]",")"]}""")
        for (character in listOf('}', ']', ')')) assertNotNull(registrations.select(null, character))
        assertNull(registrations.select(null, ';'))
    }

    fun testStaticFallbackWithoutApplicableDynamicRegistration() {
        assertNotNull(registrations.select(DocumentOnTypeFormattingOptions(";"), ';'))
        assertNull(registrations.select(DocumentOnTypeFormattingOptions(";"), '}'))
    }

    fun testUnregisterAndReregisterInvalidateCapturedResponses() {
        register("first", """{"firstTriggerCharacter":"}"}""")
        val token = registrations.select(null, '}')!!
        registrations.unregister(UnregistrationParams(listOf(Unregistration("first", METHOD))))
        assertNull(registrations.select(null, '}'))
        assertFalse(registrations.ifCurrent(token) { fail("Unregistered response must be rejected") })
        register("first", """{"firstTriggerCharacter":"]"}""")
        assertNull(registrations.select(null, '}'))
        assertNotNull(registrations.select(null, ']'))
    }

    fun testReplacementRegistrationInvalidatesCapturedResponse() {
        register("first", """{"firstTriggerCharacter":"}"}""")
        val token = registrations.select(null, '}')!!
        register("first", """{"firstTriggerCharacter":";"}""")
        assertFalse(registrations.ifCurrent(token) { fail("Old registration must be rejected") })
        assertNotNull(registrations.select(null, ';'))
    }

    fun testMalformedAndUnsupportedSelectorsNeverEnableUniversalFormatting() {
        for (options in listOf("null", "[]", "{}", """{"firstTriggerCharacter":""}""",
            """{"firstTriggerCharacter":"ab"}""", """{"firstTriggerCharacter":12}""",
            """{"firstTriggerCharacter":"}","documentSelector":[{"language":"unrelated"}]}""")) {
            register("first", options)
            assertNull(options, registrations.select(null, '}'))
        }
    }

    fun testStopRetiresPendingRegistrationCompletionAndNewStartIsIsolated() {
        val epoch = registrations.epoch()
        register("first", """{"firstTriggerCharacter":"}"}""")
        val token = registrations.select(null, '}')!!
        registrations.retire()
        assertFalse(registrations.ifCurrent(token) { fail("Stopped descriptor must reject edits") })
        assertNull(registrations.select(DocumentOnTypeFormattingOptions(";"), ';'))
        registrations.start()
        registrations.register(params("late", """{"firstTriggerCharacter":"}"}"""), epoch)
        assertNull(registrations.select(null, '}'))
        register("new", """{"firstTriggerCharacter":")"}""")
        assertNotNull(registrations.select(null, ')'))
    }

    fun testUnrelatedRegistrationsDoNotInvalidateOnTypeResponse() {
        val token = registrations.select(DocumentOnTypeFormattingOptions(";"), ';')!!
        registrations.register(RegistrationParams(listOf(Registration("other", "textDocument/hover", null))))
        var applied = false
        assertTrue(registrations.ifCurrent(token) { applied = true })
        assertTrue(applied)
    }

    private fun register(id: String, options: String) = registrations.register(params(id, options))
    private fun params(id: String, options: String) = RegistrationParams(listOf(Registration(id, METHOD, JsonParser.parseString(options))))
    companion object { private const val METHOD = "textDocument/onTypeFormatting" }
}
