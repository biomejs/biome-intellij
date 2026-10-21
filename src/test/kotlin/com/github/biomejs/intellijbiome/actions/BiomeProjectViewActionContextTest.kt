package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CopyOnWriteArrayList

class BiomeProjectViewActionContextTest : BasePlatformTestCase() {
    private val notifications = CopyOnWriteArrayList<Notification>()
    private val actions: List<AnAction> get() = listOf(BiomeApplySafeFixesAction(), BiomeSortImportAction())

    override fun setUp() {
        super.setUp()
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.MANUAL
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "Biome") notifications += notification
            }
        })
    }

    fun testSupportedSelectionIsAvailableWithoutEditor() {
        val file = myFixture.addFileToProject("selected.js", "let value=1;").virtualFile
        for (action in actions) {
            assertTrue(updated(action, file).presentation.isEnabledAndVisible)
        }
    }

    fun testDirectoryAndMissingSelectionAreHiddenAndDoNothing() {
        val directory = myFixture.tempDirFixture.findOrCreateDir("selected")
        for (file in listOf(directory, null)) {
            for (action in actions) {
                val event = updated(action, file)
                assertFalse(event.presentation.isEnabledAndVisible)
                action.actionPerformed(event)
            }
        }
        assertTrue(notifications.isEmpty())
    }

    fun testReadOnlySelectionIsHiddenAndDoesNothing() {
        val file = myFixture.addFileToProject("selected.js", "let value=1;").virtualFile
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
        document.setReadOnly(true)
        try {
            for (action in actions) {
                val event = updated(action, file)
                assertFalse(event.presentation.isEnabledAndVisible)
                action.actionPerformed(event)
            }
            assertEquals("let value=1;", document.text)
            assertTrue(notifications.isEmpty())
        } finally {
            document.setReadOnly(false)
        }
    }

    fun testReadOnlySelectionDoesNotFallBackToWritableEditor() {
        myFixture.configureByText("active.js", "let active=1;")
        val file = myFixture.addFileToProject("selected.js", "let selected=1;").virtualFile
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
        document.setReadOnly(true)
        try {
            for (action in actions) {
                val event = updated(action, file, myFixture.editor)
                assertFalse(event.presentation.isEnabledAndVisible)
                action.actionPerformed(event)
            }
            assertEquals("let active=1;", myFixture.editor.document.text)
            assertEquals("let selected=1;", document.text)
            assertTrue(notifications.isEmpty())
        } finally {
            document.setReadOnly(false)
        }
    }

    fun testDisabledIntegrationHidesSelection() {
        val file = myFixture.addFileToProject("selected.js", "let value=1;").virtualFile
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.DISABLED
        for (action in actions) {
            val event = updated(action, file)
            assertFalse(event.presentation.isEnabledAndVisible)
            action.actionPerformed(event)
        }
        assertTrue(notifications.isEmpty())
    }

    private fun updated(action: AnAction, file: VirtualFile?, editor: Editor? = null): AnActionEvent {
        val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.PROJECT_VIEW_POPUP, DataContext { key ->
            when (key) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.VIRTUAL_FILE.name -> file
                CommonDataKeys.EDITOR.name -> editor
                else -> null
            }
        })
        val future = ApplicationManager.getApplication().executeOnPooledThread {
            ReadAction.run<RuntimeException> { action.update(event) }
        }
        PlatformTestUtil.waitWithEventsDispatching("Action update did not finish", { future.isDone }, 10)
        future.get()
        return event
    }
}
