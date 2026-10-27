package com.github.biomejs.intellijbiome.lsp

import kotlinx.coroutines.*

/** Holds lifecycle work until a test explicitly drains its queued tasks. */
internal class QueuedDiscoveryDispatcher : CoroutineDispatcher() {
    private val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }

    fun drainCancelledTasks() {
        while (true) (queue.poll() ?: return).run()
    }

    fun runNextPendingTask() {
        val task = queue.poll() ?: return
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .executeOnPooledThread(task).get(5, java.util.concurrent.TimeUnit.SECONDS)
    }
}
