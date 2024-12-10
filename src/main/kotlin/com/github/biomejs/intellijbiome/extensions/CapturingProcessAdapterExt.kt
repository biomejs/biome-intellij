package com.github.biomejs.intellijbiome.extensions

import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutput
import java.util.concurrent.CompletableFuture

class ProcessResult(val processEvent: ProcessEvent, val processOutput: ProcessOutput)

val ProcessEvent.isSuccess: Boolean get() = exitCode == 0

fun OSProcessHandler.runProcessFuture(): CompletableFuture<ProcessResult> {
    val future = CompletableFuture<ProcessResult>()

    this.addProcessListener(object : CapturingProcessAdapter() {
        override fun processTerminated(event: ProcessEvent) {
            future.complete(ProcessResult(event, output))
        }
    })

    this.startNotify()

    return future
}
