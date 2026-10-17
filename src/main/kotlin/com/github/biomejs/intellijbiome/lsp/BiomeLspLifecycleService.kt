package com.github.biomejs.intellijbiome.lsp

import com.intellij.openapi.components.Service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import kotlinx.coroutines.CoroutineScope

/** Separates explicit Stop intent from automatic discovery and workspace replacement. */
@Service(Service.Level.PROJECT)
internal class BiomeLspLifecycleService(project: Project, scope: CoroutineScope) {
    private var clock = 0L
    private var providerStop = 0L
    private var startFence = 0L
    private val rootStops = mutableMapOf<String, Long>()
    private val stoppedRoots = mutableSetOf<String>()
    private val editorOpenRequests = mutableMapOf<String, Long>()

    init {
        // The SDK also calls provider.fileOpened during synthetic Restart scans. Only a
        // real editor-open notification resumes a root previously stopped by the user.
        project.messageBus.connect(scope).subscribe(FileEditorManagerListener.Before.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener.Before {
                override fun beforeFileOpened(source: FileEditorManager, file: VirtualFile) {
                    recordEditorOpen(file)
                }

                override fun beforeFileClosed(source: FileEditorManager, file: VirtualFile) {
                    takeEditorOpen(file)
                }
            })
    }

    @Synchronized
    private fun recordEditorOpen(file: VirtualFile) {
        editorOpenRequests[file.url] = clock
    }

    @Synchronized
    fun takeEditorOpen(file: VirtualFile): Long? = editorOpenRequests.remove(file.url)


    @Synchronized
    fun requestStamp(): Long = clock

    @Synchronized
    fun isCurrent(request: Long): Boolean = request == clock

    @Synchronized
    fun permits(root: VirtualFile, request: Long): Boolean =
        request >= startFence && request >= providerStop && request >= rootStops.getOrDefault(root.url, 0L)

    /** Owner replacement fences processes, but does not discard another pending config event. */
    @Synchronized
    fun allowsRecovery(request: Long): Boolean = request >= providerStop

    @Synchronized
    fun permitsRecovery(root: VirtualFile, request: Long): Boolean =
        allowsRecovery(request) && request >= rootStops.getOrDefault(root.url, 0L) && root.url !in stoppedRoots

    /** Retains a stopped former owner's veto even after the SDK removes its server. */
    @Synchronized
    fun permitsRecovery(file: VirtualFile, desired: VirtualFile, established: VirtualFile?, request: Long): Boolean {
        if (!permitsRecovery(desired, request) ||
            (established != null && !permitsRecovery(established, request))) return false
        return rootStops.none { (root, stoppedAt) ->
            stoppedAt > request && file.url.startsWith(root + "/") &&
                (established == null || root == established.url || root.startsWith(established.url + "/"))
        }
    }

    @Synchronized
    fun isStopped(root: VirtualFile): Boolean = root.url in stoppedRoots

    @Synchronized
    fun resume(root: VirtualFile, request: Long): Boolean {
        if (!allowsRecovery(request) || request < rootStops.getOrDefault(root.url, 0L)) return false
        if (stoppedRoots.remove(root.url)) ++clock
        return true
    }

    @Synchronized
    fun stop(roots: Collection<VirtualFile>) {
        val stamp = ++clock
        for (root in roots) {
            rootStops[root.url] = stamp
            stoppedRoots.add(root.url)
        }
    }

    @Synchronized
    fun stopAll(roots: Collection<VirtualFile>) {
        providerStop = ++clock
        stoppedRoots.addAll(roots.map { it.url })
    }

    @Synchronized
    fun restartAll() {
        providerStop = ++clock
        stoppedRoots.clear()
    }

    /** Invalidates pending starts without resuming roots the user deliberately stopped. */
    @Synchronized
    fun replaceOwners(): Long {
        startFence = ++clock
        return clock
    }
}
