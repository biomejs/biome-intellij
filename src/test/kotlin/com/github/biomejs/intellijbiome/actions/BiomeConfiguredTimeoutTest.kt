package com.github.biomejs.intellijbiome.actions

import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class BiomeConfiguredTimeoutTest : BasePlatformTestCase() {
    fun testShorterOverrideLimitsTheOperation() {
        setTimeout(20)
        runScheduled {
            assertSame(BiomeSaveOutcome.TimedOut, runBiomeSaveOperation { delay(21) })
        }
    }

    fun testLongerOverrideAllowsWorkPastTheDefault() {
        setTimeout(7_000)
        runScheduled {
            assertSame(BiomeSaveOutcome.Completed, runBiomeSaveOperation { delay(6_000) })
        }
    }

    fun testDefaultRemainsFiveSeconds() {
        setTimeout(5_000)
        runScheduled {
            assertSame(BiomeSaveOutcome.Completed, runBiomeSaveOperation { delay(4_999) })
            assertSame(BiomeSaveOutcome.TimedOut, runBiomeSaveOperation { delay(5_001) })
        }
    }

    fun testNonpositiveValuesUseTheDefault() {
        for (value in listOf(0, -1)) {
            setTimeout(value)
            runScheduled {
                assertSame(BiomeSaveOutcome.Completed, runBiomeSaveOperation { delay(4_999) })
                assertSame(BiomeSaveOutcome.TimedOut, runBiomeSaveOperation { delay(5_001) })
            }
        }
    }

    fun testOuterCancellationStillPropagatesWithOverride() {
        setTimeout(1_000)
        runScheduled {
            var returned = false
            var failure: Throwable? = null
            try {
                withTimeout(10) {
                    runBiomeSaveOperation { awaitCancellation() }
                    returned = true
                }
            } catch (actual: TimeoutCancellationException) {
                failure = actual
            }
            assertFalse(returned)
            assertNotNull(failure)
        }
    }

    private fun setTimeout(value: Int) {
        Registry.get("biome.actions.timeout.ms").setValue(value, testRootDisposable)
    }
}
