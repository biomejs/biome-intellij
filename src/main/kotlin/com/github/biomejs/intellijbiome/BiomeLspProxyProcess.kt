package com.github.biomejs.intellijbiome

import com.intellij.execution.KillableProcess
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessTreeKiller
import com.intellij.execution.process.SelfKiller
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.BaseOutputReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only local Unix LSP proxies have the process ownership covered by these handlers. */
internal fun isLocalBiomeLspProxy(parameters: List<String>, executable: String?, workingDirectory: String?): Boolean =
    (SystemInfoRt.isMac || SystemInfoRt.isLinux) && parameters == listOf("lsp-proxy") &&
        listOfNotNull(executable, workingDirectory).none { it.replace('\\', '/').startsWith("//") }

/**
 * Keep the SDK's Node handler while directing both its destroy and hard-kill paths
 * through this client's process ownership. SelfKiller's existing default is used;
 * the experimental graceful-destruction hook is not overridden.
 */
internal class BiomeLspProxyProcess(private val delegate: Process) : Process(), SelfKiller, ProcessTreeKiller {
    private val lifetime = BiomeLspProxyLifetime(delegate)

    override fun getOutputStream() = delegate.outputStream
    override fun getInputStream() = delegate.inputStream
    override fun getErrorStream() = delegate.errorStream
    override fun waitFor() = delegate.waitFor()
    override fun waitFor(timeout: Long, unit: TimeUnit) = delegate.waitFor(timeout, unit)
    override fun exitValue() = delegate.exitValue()
    override fun isAlive() = delegate.isAlive
    override fun pid() = delegate.pid()
    override fun toHandle() = delegate.toHandle()
    override fun children() = delegate.children()
    override fun descendants() = delegate.descendants()
    override fun supportsNormalTermination() = delegate.supportsNormalTermination()
    override fun onExit(): CompletableFuture<Process> = delegate.onExit().thenApply { this }

    override fun destroy() = lifetime.destroy()

    override fun destroyForcibly(): Process {
        lifetime.destroyForcibly()
        return this
    }

    override fun killProcessTree(): Boolean {
        lifetime.destroyForcibly()
        return true
    }
}

/** Preserve the normal GeneralCommandLine construction and capture/read behavior. */
internal class BiomeLspProxyProcessHandler(command: GeneralCommandLine) : CapturingProcessHandler(command), KillableProcess {
    private val lifetime = BiomeLspProxyLifetime(process)

    override fun readerOptions(): BaseOutputReader.Options = object : BaseOutputReader.Options() {
        override fun splitToLines() = false
    }

    override fun doDestroyProcess() = lifetime.destroy()
    override fun killProcessTree(process: Process) = lifetime.destroyForcibly()
    override fun canKillProcess() = true
    override fun killProcess() = lifetime.destroyForcibly()
}

/**
 * The npm launcher owns a native `lsp-proxy` child; a manual shell can add one parent.
 * Inspect only these two levels and never enter a proxy or `__run_server` daemon
 * subtree. Retain matching proxy handles for escalation after the launcher exits.
 */
private class BiomeLspProxyLifetime(private val process: Process) {
    private val stopRequested = AtomicBoolean()
    private var owned: List<ProcessHandle>? = null

    @Synchronized
    private fun ownedProcesses(): List<ProcessHandle> = owned ?: run {
        val root = process.toHandle()
        val proxies = mutableListOf<ProcessHandle>()
        root.children().use { children ->
            children.forEach { child ->
                val arguments = child.info().arguments().orElse(null) ?: return@forEach
                if ("__run_server" in arguments) return@forEach
                if (arguments.firstOrNull() == "lsp-proxy") {
                    proxies += child
                } else {
                    // A shell may be waiting for npm. Signal its proxy, not the intermediate launcher.
                    child.children().use { grandchildren ->
                        grandchildren.filter {
                            val nestedArguments = it.info().arguments().orElse(null)
                            nestedArguments != null && nestedArguments.firstOrNull() == "lsp-proxy" &&
                                "__run_server" !in nestedArguments
                        }.forEach { proxies += it }
                    }
                }
            }
        }
        (proxies + root).also { owned = it }
    }

    fun destroy() {
        if (!stopRequested.compareAndSet(false, true)) return
        val processes = ownedProcesses()
        processes.forEach { if (it.isAlive) it.destroy() }
        // A stopped proxy cannot handle TERM. Escalate only the captured handles,
        // using the shared scheduler without waiting on the caller or creating threads.
        try {
            AppExecutorUtil.getAppScheduledExecutorService().schedule({ force(processes) }, 1, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) {
            force(processes)
        }
    }

    fun destroyForcibly() = force(ownedProcesses())

    private fun force(processes: List<ProcessHandle>) {
        processes.forEach { if (it.isAlive) it.destroyForcibly() }
    }
}
