package com.github.biomejs.intellijbiome.lsp

import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.ManagingFS
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import junit.framework.TestCase.assertFalse
import java.lang.reflect.InvocationTargetException

private val flushPendingFileUpdates by lazy {
    try {
        ManagingFS::class.java.getMethod("flushPendingUpdates", VirtualFile::class.java)
    } catch (missing: NoSuchMethodException) {
        check(ApplicationInfo.getInstance().build.baselineVersion == 253) {
            "Current IDE must expose the physical file-write completion barrier"
        }
        null // 253 FileDocumentManager writes synchronously.
    }
}

/** 262 can finish document saving before its requestor's physical VFS write. */
internal fun awaitPhysicalFileWrites(file: VirtualFile) {
    val flush = flushPendingFileUpdates ?: return
    try {
        flush.invoke(ManagingFS.getInstance(), file)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}

/** Uses the SDK's save jobs as the completion signal, not an empty event queue. */
internal fun saveAndAwaitCompletion(fixture: CodeInsightTestFixture, documents: List<Document>) {
    val fileManager = FileDocumentManager.getInstance()
    fileManager.saveAllDocuments()
    val actions = ActionsOnSaveManager.getInstance(fixture.project)
    PlatformTestUtil.waitWithEventsDispatching("Save actions did not finish", { !actions.hasPendingActions() }, 30)
    documents.forEach { document -> fileManager.getFile(document)?.let { awaitPhysicalFileWrites(it) } }
    documents.forEach { assertFalse("Document must reach disk after save actions", fileManager.isDocumentUnsaved(it)) }
}
