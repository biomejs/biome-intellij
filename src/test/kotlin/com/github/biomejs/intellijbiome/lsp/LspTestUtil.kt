package com.github.biomejs.intellijbiome.lsp

import com.intellij.injected.editor.DocumentWindow
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerManagerListener
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.ExpectedHighlightingData
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert
import org.junit.ComparisonFailure

private const val DEFAULT_DIAGNOSTICS_TIMEOUT = 30
private const val DEFAULT_RETRY_TIMEOUT = 5

fun waitUntilFileOpenedByLspServer(
    project: Project,
    file: VirtualFile,
    descriptorClassName: String? = null,
    timeout: Int = 10,
) {
    val topLevelFile = (file as? VirtualFileWindow)?.delegate ?: file
    val disposable = Disposer.newDisposable()
    try {
        val fileOpened = AtomicBoolean()
        val serverShutdown = AtomicBoolean()
        LspServerManager.getInstance(project).addLspServerManagerListener(object : LspServerManagerListener {
            override fun serverStateChanged(lspServer: LspServer) {
                if (!matchesDescriptor(lspServer, descriptorClassName)) {
                    return
                }
                if (lspServer.state in arrayOf(LspServerState.ShutdownNormally, LspServerState.ShutdownUnexpectedly)) {
                    serverShutdown.set(true)
                }
            }

            override fun fileOpened(lspServer: LspServer, file: VirtualFile) {
                if (!matchesDescriptor(lspServer, descriptorClassName)) {
                    return
                }
                if (file == topLevelFile) {
                    fileOpened.set(true)
                }
            }
        }, disposable, true)

        PlatformTestUtil.waitWithEventsDispatching(
            "LSP server not initialized for ${topLevelFile.name} in $timeout seconds",
            {
                ProgressManager.checkCanceled()
                fileOpened.get() || serverShutdown.get()
            },
            timeout
        )
        Assert.assertFalse("LSP server initialization failed", serverShutdown.get())
    } finally {
        Disposer.dispose(disposable)
    }
}

fun waitForDiagnosticsFromLspServer(
    project: Project,
    file: VirtualFile,
    descriptorClassName: String? = null,
    timeout: Int = DEFAULT_DIAGNOSTICS_TIMEOUT,
) {
    withDiagnosticsReceivedCounter(project, file, descriptorClassName) { diagnosticsReceivedCounter ->
        doWaitForDiagnosticsFromLspServer(diagnosticsReceivedCounter, timeout)
    }
}

fun CodeInsightTestFixture.checkLspHighlighting() {
    val document = editor.document.let { (it as? DocumentWindow)?.delegate ?: it }
    val data = ExpectedHighlightingData(document, true, true, false)
    data.init()
    checkLspHighlightingForData(data)
}

fun CodeInsightTestFixture.checkLspHighlightingForData(
    data: ExpectedHighlightingData,
    descriptorClassName: String? = null,
    initialTimeout: Int = DEFAULT_DIAGNOSTICS_TIMEOUT,
    retryTimeout: Int = DEFAULT_RETRY_TIMEOUT,
) {
    withDiagnosticsReceivedCounter(project, file.virtualFile, descriptorClassName) { diagnosticsReceivedCounter ->
        doWaitForDiagnosticsFromLspServer(diagnosticsReceivedCounter, timeout = initialTimeout, attemptNumber = 1)
        doCheckExpectedHighlightingData(
            this as CodeInsightTestFixtureImpl,
            data,
            diagnosticsReceivedCounter,
            attemptNumber = 1,
            retryTimeout = retryTimeout
        )
    }
}

private fun doWaitForDiagnosticsFromLspServer(
    diagnosticsReceivedCounter: DiagnosticsReceivedCounter,
    timeout: Int,
    attemptNumber: Int = 1,
) {
    PlatformTestUtil.waitWithEventsDispatching(
        "Diagnostics from server for file ${diagnosticsReceivedCounter.file.name} not received in $timeout seconds",
        {
            ProgressManager.checkCanceled()
            diagnosticsReceivedCounter.diagnosticsReceivedCount >= attemptNumber || diagnosticsReceivedCounter.serverShutdown
        },
        timeout
    )

    Assert.assertFalse("LSP server initialization failed", diagnosticsReceivedCounter.serverShutdown)
}

private fun doCheckExpectedHighlightingData(
    fixture: CodeInsightTestFixtureImpl,
    data: ExpectedHighlightingData,
    diagnosticsReceivedCounter: DiagnosticsReceivedCounter,
    attemptNumber: Int,
    retryTimeout: Int,
) {
    val maxAttempts = 3

    try {
        fixture.collectAndCheckHighlighting(data)
    } catch (comparisonFailure: ComparisonFailure) {
        val nextAttemptNumber = attemptNumber + 1
        if (nextAttemptNumber > maxAttempts) {
            throw comparisonFailure
        }

        try {
            doWaitForDiagnosticsFromLspServer(
                diagnosticsReceivedCounter,
                timeout = retryTimeout,
                attemptNumber = nextAttemptNumber
            )
        } catch (_: AssertionError) {
            throw comparisonFailure
        }

        doCheckExpectedHighlightingData(
            fixture,
            data,
            diagnosticsReceivedCounter,
            attemptNumber = nextAttemptNumber,
            retryTimeout = retryTimeout
        )
    }
}

private inline fun <T> withDiagnosticsReceivedCounter(
    project: Project,
    file: VirtualFile,
    descriptorClassName: String? = null,
    block: (DiagnosticsReceivedCounter) -> T,
): T {
    val topLevelFile = (file as? VirtualFileWindow)?.delegate ?: file
    val disposable = Disposer.newDisposable()
    try {
        val diagnosticsReceivedCounter = DiagnosticsReceivedCounter(topLevelFile, descriptorClassName)
        LspServerManager.getInstance(project).addLspServerManagerListener(
            diagnosticsReceivedCounter,
            disposable,
            true
        )
        return block(diagnosticsReceivedCounter)
    } finally {
        Disposer.dispose(disposable)
    }
}

private fun matchesDescriptor(lspServer: LspServer, descriptorClassName: String?): Boolean {
    return descriptorClassName == null || lspServer.descriptor.javaClass.name == descriptorClassName
}

private class DiagnosticsReceivedCounter(
    val file: VirtualFile,
    private val descriptorClassName: String?,
) : LspServerManagerListener {
    @Volatile
    var serverShutdown: Boolean = false
        private set

    private val diagnosticsReceived = AtomicInteger(0)
    val diagnosticsReceivedCount: Int
        get() = diagnosticsReceived.get()

    override fun serverStateChanged(lspServer: LspServer) {
        if (!matchesDescriptor(lspServer, descriptorClassName)) {
            return
        }
        if (lspServer.state in arrayOf(LspServerState.ShutdownNormally, LspServerState.ShutdownUnexpectedly)) {
            serverShutdown = true
        }
    }

    override fun diagnosticsReceived(lspServer: LspServer, file: VirtualFile) {
        if (!matchesDescriptor(lspServer, descriptorClassName)) {
            return
        }
        if (file == this.file) {
            diagnosticsReceived.incrementAndGet()
        }
    }
}
