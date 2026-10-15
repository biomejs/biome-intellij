package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.services.BiomeServerService.Feature
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.EnumSet

class BiomeLegacySaveActionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            formatOnSave = false
            sortImportOnSave = false
            applySafeFixesOnSave = false
        }
    }

    override fun tearDown() {
        try {
            BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.DISABLED
        } finally {
            super.tearDown()
        }
    }

    fun testIneligibleDocumentsAreSkipped() = runAction {
        var calls = 0
        val action = BiomeCheckOnSaveAction { _, _, _ -> calls++ }
        val settings = BiomeSettings.getInstance(project)
        val supported = myFixture.configureByText("index.js", "let x = 1;").viewProvider.document!!
        settings.configurationMode = ConfigurationMode.DISABLED
        settings.formatOnSave = true
        action.updateDocument(project, supported)
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.formatOnSave = false
        action.updateDocument(project, supported)
        settings.formatOnSave = true
        action.updateDocument(project, myFixture.configureByText("other.txt", "text").viewProvider.document!!)
        action.updateDocument(project, EditorFactory.getInstance().createDocument("unbacked"))
        assertEquals(0, calls)
    }

    fun testDisabledPreferencesExecuteNoSaveWork() = runAction {
        val settings = BiomeSettings.getInstance(project)
        settings.enableLspFormat = true
        settings.formatOnSave = true
        settings.applySafeFixesOnSave = true
        settings.sortImportOnSave = true
        settings.configurationMode = ConfigurationMode.DISABLED
        val document = myFixture.configureByText("disabled.js", "let x=1").viewProvider.document!!
        val action = BiomeCheckOnSaveAction { _, _, _ -> fail("Disabled integration must execute no Biome work") }
        assertFalse(action.isEnabledForProject(project))
        action.updateDocument(project, document)
        assertEquals("let x=1", document.text)
        assertTrue(settings.state.formatOnSave)
        assertTrue(settings.state.applySafeFixesOnSave)
        assertTrue(settings.state.sortImportOnSave)
    }

    fun testFeatureSnapshotIsStable() = runAction {
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.formatOnSave = true
        settings.sortImportOnSave = false
        settings.applySafeFixesOnSave = false
        val document = myFixture.configureByText("index.js", "let x = 1;").viewProvider.document!!
        val snapshots = mutableListOf<EnumSet<Feature>>()
        val action = BiomeCheckOnSaveAction { _, _, features ->
            settings.formatOnSave = false
            settings.sortImportOnSave = true
            snapshots += features
        }
        action.updateDocument(project, document)
        action.updateDocument(project, document)
        assertEquals(listOf(EnumSet.of(Feature.Format), EnumSet.of(Feature.SortImports)), snapshots)
    }

    fun testPlatformCancellationPropagates() = runAction {
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.formatOnSave = true
        val document = myFixture.configureByText("index.js", "let x = 1;").viewProvider.document!!
        val cancellation = ProcessCanceledException()
        val action = BiomeCheckOnSaveAction { _, _, _ -> throw cancellation }
        var thrown: Throwable? = null
        try { action.updateDocument(project, document) } catch (failure: Throwable) { thrown = failure }
        assertTrue(thrown is ProcessCanceledException || thrown is CancellationException)
    }
    fun testFileSpecificFailureFeedback() = runAction {
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.formatOnSave = true
        val document = myFixture.configureByText("failure.js", "let x = 1;").viewProvider.document!!
        val notifications = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "Biome") notifications += notification
            }
        })
        val warnings = CopyOnWriteArrayList<String>()
        LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                if (category.contains("BiomeCheckOnSaveAction")) warnings += message
                return false
            }
        }).use {
            BiomeCheckOnSaveAction { _, _, _ -> error("controlled failure") }.updateDocument(project, document)
            assertEquals(1, notifications.size)
            assertTrue(notifications.single().title.contains("failure.js"))
            assertTrue(warnings.single().contains("failure.js"))
            notifications.forEach { it.expire() }
            notifications.clear()
            warnings.clear()
            BiomeCheckOnSaveAction { _, _, _ -> awaitCancellation() }.updateDocument(project, document)
            assertTrue(notifications.isEmpty())
            assertTrue(warnings.single().contains("failure.js"))
            warnings.clear()
            val cancellation = CancellationException("outer cancellation")
            try {
                BiomeCheckOnSaveAction { _, _, _ -> throw cancellation }.updateDocument(project, document)
                fail("Cancellation must propagate")
            } catch (failure: CancellationException) {
                // The modal boundary and coroutine stack recovery can wrap/copy cancellation.
                assertTrue(generateSequence<Throwable>(failure) { it.cause }.take(10)
                    .any { it.message == "outer cancellation" })
            }
            assertTrue(warnings.isEmpty())
            assertTrue(notifications.isEmpty())
        }
    }

    private fun runAction(operation: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val result = AtomicReference<Result<Unit>>()
        try {
            scope.launch { result.set(runCatching { operation() }) }
            PlatformTestUtil.waitWithEventsDispatching("Action policy test did not finish", { result.get() != null }, 15)
            result.get().getOrThrow()
        } finally {
            scope.cancel()
        }
    }

}
