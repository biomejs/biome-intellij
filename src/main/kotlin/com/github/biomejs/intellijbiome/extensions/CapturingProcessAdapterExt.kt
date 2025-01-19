package com.github.biomejs.intellijbiome.extensions

import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutput
import java.util.concurrent.CompletableFuture

class ProcessResult( val processOutput: ProcessOutput)


fun runProcessFuture(handler: OSProcessHandler): CompletableFuture<ProcessResult> {
    val future = CompletableFuture<ProcessResult>()

    handler.addProcessListener(object : CapturingProcessAdapter() {
        override fun processTerminated(event: ProcessEvent) {
            future.complete(ProcessResult(output))
        }
    })

    handler.startNotify()

    return future
}
