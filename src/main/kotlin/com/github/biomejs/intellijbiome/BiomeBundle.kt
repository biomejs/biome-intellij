package com.github.biomejs.intellijbiome

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.BiomeBundle"

object BiomeBundle {
    private val bundle = DynamicBundle(BiomeBundle::class.java, BUNDLE)

    @Suppress("SpreadOperator")
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any) =
        bundle.getMessage(key, *params)

    @Suppress("SpreadOperator", "unused")
    @JvmStatic
    fun messagePointer(@PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any) =
        bundle.getLazyMessage(key, *params)
}
