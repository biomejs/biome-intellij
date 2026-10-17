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
    fun permits(root: VirtualFile, request: Long): Boolean =
        request >= providerStop && request >= rootStops.getOrDefault(root.url, 0L)

    @Synchronized
    fun isStopped(root: VirtualFile): Boolean = root.url in stoppedRoots

    @Synchronized
    fun resume(root: VirtualFile, request: Long): Boolean {
        if (!permits(root, request)) return false
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
}
