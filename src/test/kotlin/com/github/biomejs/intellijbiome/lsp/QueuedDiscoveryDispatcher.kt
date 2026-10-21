package com.github.biomejs.intellijbiome.lsp

import com.intellij.openapi.application.EDT
import kotlinx.coroutines.*

/** Drives recovery reads while leaving their real EDT continuation queued. */
internal class QueuedDiscoveryDispatcher : CoroutineDispatcher() {
    private val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }

    fun drainCancelledTasks() {
        while (true) (queue.poll() ?: return).run()
    }

    fun runPendingReads(scope: CoroutineScope, expectEdt: Boolean = false) {
        val application = com.intellij.openapi.application.ApplicationManager.getApplication()
        check(application.isDispatchThread && !application.isWriteAccessAllowed)
        // A read can finish before suspension and need no return dispatch. Observe
        // the real EDT child instead of assuming a fixed number of queued tasks.
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val active = scope.coroutineContext.job.children.filter { it.isActive }.toList()
            if (active.isEmpty()) {
                check(!expectEdt) { "Discovery completed without queuing the expected EDT action" }
                return
            }
            if (active.all(::waitingOnEdt)) {
                check(expectEdt) { "Discovery unexpectedly queued an EDT action" }
                return
            }
            queue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { task ->
                application.executeOnPooledThread(task).get(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        error("Discovery did not finish its pooled read or reach the held EDT")
    }

    fun runNextPendingTask() {
        val task = queue.poll() ?: return
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .executeOnPooledThread(task).get(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun waitingOnEdt(job: Job): Boolean =
        (job as? CoroutineScope)?.coroutineContext?.get(kotlin.coroutines.ContinuationInterceptor) == Dispatchers.EDT ||
            job.children.any(::waitingOnEdt)
}
