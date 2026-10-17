package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CopyOnWriteArrayList

class BiomeManualActionContextTest : BasePlatformTestCase() {
    private val notifications = CopyOnWriteArrayList<Notification>()
    private val actions: List<AnAction> get() = listOf(BiomeApplySafeFixesAction(), BiomeSortImportAction())

    override fun setUp() {
        super.setUp()
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.MANUAL
        myFixture.configureByText("context.js", "let value=1;")
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "Biome") notifications += notification
            }
        })
    }

    fun testSupportedEditorIsAvailableOnBackgroundThread() {
        for (action in actions) {
            assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
            assertTrue(updated(action).presentation.isEnabledAndVisible)
        }
    }

    fun testMissingEditorIsHiddenAndDoesNothing() {
        for (action in actions) {
            val event = updated(action, null)
            assertFalse(event.presentation.isEnabledAndVisible)
            action.actionPerformed(event)
        }
        assertTrue(notifications.isEmpty())
    }

    fun testDisabledIntegrationIsHiddenAndDoesNothing() {
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.DISABLED
        for (action in actions) {
            val event = updated(action)
            assertFalse(event.presentation.isEnabledAndVisible)
            action.actionPerformed(event)
        }
        assertEquals("let value=1;", myFixture.editor.document.text)
        assertTrue(notifications.isEmpty())
    }

    fun testReadOnlyDocumentIsHiddenAndDoesNothing() {
        myFixture.editor.document.setReadOnly(true)
        try {
            for (action in actions) {
                val event = updated(action)
                assertFalse(event.presentation.isEnabledAndVisible)
                action.actionPerformed(event)
            }
            assertTrue(notifications.isEmpty())
        } finally {
            myFixture.editor.document.setReadOnly(false)
        }
    }

    fun testViewerOfWritableDocumentIsHidden() {
        val viewer = EditorFactory.getInstance().createViewer(myFixture.editor.document, project)
        try {
            assertTrue(viewer.document.isWritable)
            for (action in actions) {
                assertFalse(updated(action, viewer).presentation.isEnabledAndVisible)
            }
        } finally {
            EditorFactory.getInstance().releaseEditor(viewer)
        }
    }

    fun testViewerDirectInvocationDoesNothing() {
        val viewer = EditorFactory.getInstance().createViewer(myFixture.editor.document, project)
        try {
            assertTrue(viewer.document.isWritable)
            for (action in actions) {
                action.actionPerformed(updated(action, viewer))
                assertEquals("let value=1;", viewer.document.text)
                assertTrue("Viewer actions must not run or report a result", notifications.isEmpty())
            }
        } finally {
            EditorFactory.getInstance().releaseEditor(viewer)
        }
    }

    fun testDisposedEditorIsHiddenAndDoesNothing() {
        val editor = EditorFactory.getInstance().createEditor(myFixture.editor.document, project)
        EditorFactory.getInstance().releaseEditor(editor)
        for (action in actions) {
            val event = updated(action, editor)
            assertFalse(event.presentation.isEnabledAndVisible)
            action.actionPerformed(event)
        }
        assertTrue(notifications.isEmpty())
    }

    fun testUnsupportedFileIsHiddenAndDirectInvocationExplainsWhy() {
        myFixture.configureByText("notes.unsupported", "plain text")
        for (action in actions) {
            notifications.clear()
            val event = updated(action)
            assertFalse(event.presentation.isEnabledAndVisible)
            action.actionPerformed(event)
            assertEquals("File not supported", notifications.single().title)
            assertEquals("plain text", myFixture.editor.document.text)
        }
    }

    private fun updated(action: AnAction, editor: Editor? = myFixture.editor): AnActionEvent {
        val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.EDITOR_POPUP, DataContext { key ->
            when (key) {
                CommonDataKeys.PROJECT.name -> project
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
