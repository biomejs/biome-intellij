package com.github.biomejs.intellijbiome.actions

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.diagnostic.ControlFlowException
import junit.framework.TestCase
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

class BiomeSaveOperationTest : TestCase() {
    fun testCompletionAndFailure() = runScheduled {
        var ran = false
        assertSame(BiomeSaveOutcome.Completed, runBiomeSaveOperation { ran = true })
        assertTrue(ran)
        val failure = IllegalStateException("controlled failure")
        val outcome = runBiomeSaveOperation { throw failure }
        assertTrue(outcome is BiomeSaveOutcome.Failed)
        assertSame(failure, (outcome as BiomeSaveOutcome.Failed).cause)
    }

    fun testOwnTimeoutIsRecoverable() = runScheduled {
        assertSame(BiomeSaveOutcome.TimedOut, runBiomeSaveOperation(100) { awaitCancellation() })
        assertSame(BiomeSaveOutcome.Completed, runBiomeSaveOperation(100) {})
    }

    fun testParentCancellationPropagates() = runScheduled {
        val cancellation = CancellationException("controlled parent cancellation")
        val parent = SupervisorJob(coroutineContext[kotlinx.coroutines.Job])
        var thrown: Throwable? = null
        var returned = false
        var deliveredToOperation: Throwable? = null
        CoroutineScope(coroutineContext + parent).launch {
            try {
                runBiomeSaveOperation(100) {
                    parent.cancel(cancellation)
                    try {
                        awaitCancellation()
                    } catch (failure: CancellationException) {
                        deliveredToOperation = failure
                        throw failure
                    }
                }
                returned = true
            } catch (failure: Throwable) {
                thrown = failure
            }
        }.join()
        assertFalse("Parent cancellation must not return an outcome", returned)
        // Coroutine debug stack recovery may copy cancellation before delivering it
        // to the operation. The helper must preserve the delivered boundary object.
        assertTrue(deliveredToOperation === cancellation || deliveredToOperation?.cause === cancellation)
        assertSame(deliveredToOperation, thrown)
    }

    fun testOuterTimeoutPropagates() = runScheduled {
        var returned = false
        var thrown: Throwable? = null
        try {
            withTimeout(50) {
                runBiomeSaveOperation(100) { awaitCancellation() }
                returned = true
            }
        } catch (failure: Throwable) {
            thrown = failure
        }
        assertFalse("Enclosing timeout must not return a local outcome", returned)
        assertTrue(thrown is TimeoutCancellationException)
    }

    fun testPlatformCancellationPropagates() = runScheduled {
        val cancellation = ProcessCanceledException()
        var thrown: Throwable? = null
        try {
            runBiomeSaveOperation { throw cancellation }
        } catch (failure: Throwable) {
            thrown = failure
        }
        assertSame(cancellation, thrown)
    }

    fun testPlatformControlFlowPropagates() = runScheduled {
        val cancellation = TestControlFlowException()
        var thrown: Throwable? = null
        try {
            runBiomeSaveOperation { throw cancellation }
        } catch (failure: Throwable) {
            thrown = failure
        }
        assertSame(cancellation, thrown)
    }

    private class TestControlFlowException : RuntimeException(), ControlFlowException

    fun testFeatureStagesShareOneBudget() = runScheduled {
        val completed = mutableListOf<String>()
        val outcome = runBiomeSaveOperation(100) {
            for (stage in listOf("fixes", "imports", "format")) {
                delay(40)
                completed += stage
            }
        }
        assertSame(BiomeSaveOutcome.TimedOut, outcome)
        assertEquals(listOf("fixes", "imports"), completed)
    }

    fun testCancellationExceptionFromOperationIsPreserved() = runScheduled {
        val cancellation = CancellationException("operation cancellation")
        var thrown: Throwable? = null
        try {
            runBiomeSaveOperation { throw cancellation }
        } catch (failure: Throwable) {
            thrown = failure
        }
        assertSame(cancellation, thrown)
    }
}

/** A deterministic timer for the existing coroutine dependency; no wall-clock sleeps. */
@OptIn(InternalCoroutinesApi::class)
private class SaveTestScheduler : CoroutineDispatcher(), Delay {
    private class Task(val at: Long, val sequence: Long, val runnable: Runnable) : Comparable<Task>, DisposableHandle {
        var disposed = false
        override fun compareTo(other: Task): Int = compareValuesBy(this, other, Task::at, Task::sequence)
        override fun dispose() { disposed = true }
    }

    private val tasks = PriorityQueue<Task>()
    private var now = 0L
    private var sequence = 0L

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        schedule(0, block)
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val task = schedule(timeMillis, Runnable { continuation.resume(Unit) })
        continuation.invokeOnCancellation { task.dispose() }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
        schedule(timeMillis, block)

    private fun schedule(delay: Long, runnable: Runnable): Task =
        Task(now + delay, sequence++, runnable).also(tasks::add)

    fun drain() {
        var count = 0
        while (tasks.isNotEmpty()) {
            check(++count <= 10_000) { "Coroutine test did not settle" }
            val task = tasks.remove()
            if (!task.disposed) {
                now = task.at
                task.runnable.run()
            }
        }
    }
}

private fun runScheduled(block: suspend CoroutineScope.() -> Unit) {
    val scheduler = SaveTestScheduler()
    val scope = CoroutineScope(SupervisorJob() + scheduler)
    var result: Result<Unit>? = null
    try {
        scope.launch { result = runCatching { block() } }
        scheduler.drain()
        checkNotNull(result) { "Test suspended without a pending event" }.getOrThrow()
    } finally {
        scope.cancel()
        scheduler.drain()
    }
}
