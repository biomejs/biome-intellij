package com.github.biomejs.intellijbiome.actions

import com.intellij.openapi.diagnostic.ControlFlowException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface BiomeSaveOutcome {
    data object Completed : BiomeSaveOutcome
    data object TimedOut : BiomeSaveOutcome
    data class Failed(val cause: Exception) : BiomeSaveOutcome
}

internal suspend fun runBiomeSaveOperation(
    timeoutMs: Long = 5_000,
    operation: suspend () -> Unit,
): BiomeSaveOutcome {
    var cancellationFromOperation: CancellationException? = null
    return try {
        // Catch ordinary failures inside the timeout scope so coroutine stack recovery
        // cannot replace the original cause object on its way across that boundary.
        withTimeoutOrNull(timeoutMs) {
            try {
                operation()
                BiomeSaveOutcome.Completed
            } catch (cancellation: CancellationException) {
                cancellationFromOperation = cancellation
                throw cancellation
            } catch (failure: Exception) {
                if (failure is ControlFlowException) throw failure
                BiomeSaveOutcome.Failed(failure)
            }
        } ?: BiomeSaveOutcome.TimedOut
    } catch (cancellation: CancellationException) {
        throw cancellationFromOperation ?: cancellation
    }
}
