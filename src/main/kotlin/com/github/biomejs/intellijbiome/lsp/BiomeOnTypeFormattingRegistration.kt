package com.github.biomejs.intellijbiome.lsp

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.eclipse.lsp4j.DocumentOnTypeFormattingRegistrationOptions
import org.eclipse.lsp4j.DocumentOnTypeFormattingOptions
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.UnregistrationParams

internal class BiomeOnTypeFormattingRegistration {
    private var lifetime = 0L
    private var revision = 0L
    private var retired = false
    private val triggers = mutableMapOf<String, Set<Char>>()

    @Synchronized fun epoch(): Long = lifetime

    @Synchronized
    fun register(params: RegistrationParams, epoch: Long = epoch()) {
        if (retired || lifetime != epoch) return
        for (registration in params.registrations) {
            if (registration.method != METHOD) continue
            ++revision
            triggers.remove(registration.id)
            val decoded = decode(registration.registerOptions) ?: continue
            if (triggers.size < 128) triggers[registration.id] = decoded
        }
    }

    @Synchronized
    fun unregister(params: UnregistrationParams, epoch: Long = epoch()) {
        if (retired || lifetime != epoch) return
        for (registration in params.unregisterations) {
            if (registration.method != METHOD) continue
            ++revision
            triggers.remove(registration.id)
        }
    }

    @Synchronized
    fun select(fallback: DocumentOnTypeFormattingOptions?, character: Char): Long? {
        if (retired) return null
        val advertised = if (triggers.isEmpty()) characters(fallback) else triggers.values.flatten().toSet()
        return revision.takeIf { character in advertised }
    }

    /** Keep registration retirement/change atomic with the final guarded edit. */
    @Synchronized
    fun ifCurrent(token: Long, action: () -> Unit): Boolean {
        if (retired || revision != token) return false
        action()
        return true
    }

    @Synchronized fun retire() { retired = true; ++lifetime; ++revision; triggers.clear() }
    @Synchronized fun start() { retired = false; ++lifetime; ++revision; triggers.clear() }

    private fun decode(options: Any?): Set<Char>? = try {
        val json = (options as? JsonElement ?: GSON.toJsonTree(options))
        if (!json.isJsonObject) null else {
            val value = json.asJsonObject
            val first = value.get("firstTriggerCharacter")
            val more = value.get("moreTriggerCharacter")
            val selector = value.get("documentSelector")
            // Biome registers a null selector. Reject selectors we cannot correctly match;
            // never turn a language/glob restriction into a universal registration.
            if ((selector != null && !selector.isJsonNull) || first == null ||
                !first.isJsonPrimitive || !first.asJsonPrimitive.isString ||
                (more != null && !more.isJsonNull && (!more.isJsonArray || more.asJsonArray.size() > 128 ||
                    more.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }))) null
            else GSON.fromJson(value, DocumentOnTypeFormattingRegistrationOptions::class.java).let {
                characters(it.firstTriggerCharacter, it.moreTriggerCharacter).takeIf { chars -> chars.isNotEmpty() }
            }
        }
    } catch (_: com.google.gson.JsonParseException) { null }

    private fun characters(options: DocumentOnTypeFormattingOptions?): Set<Char> {
        if (options == null) return emptySet()
        return characters(options.firstTriggerCharacter, options.moreTriggerCharacter)
    }

    private fun characters(first: String?, more: List<String>?): Set<Char> {
        val values = listOf(first) + more.orEmpty()
        if (values.size > 129 || values.any { it == null || it.length != 1 || it[0].isSurrogate() }) return emptySet()
        return values.map { it!![0] }.toSet()
    }

    private companion object {
        const val METHOD = "textDocument/onTypeFormatting"
        val GSON = Gson()
    }
}
