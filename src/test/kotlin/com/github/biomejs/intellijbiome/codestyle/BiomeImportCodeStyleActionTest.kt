package com.github.biomejs.intellijbiome.codestyle

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BiomeImportCodeStyleActionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.MANUAL
    }

    fun testExplicitImportActionIsRegistered() {
        assertNotNull("Code style import must be an explicit action", ActionManager.getInstance().getAction(ACTION_ID))
    }

    fun testSelectedJavaScriptAndTypeScriptFilesAreAvailable() {
        for (extension in listOf("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts")) {
            val file = myFixture.addFileToProject("selected.$extension", "let value=1;").virtualFile
            val event = updated(file)
            assertTrue("Selected $extension file must offer import", event.presentation.isEnabledAndVisible)
        }
    }

    fun testUnsupportedAndMissingFilesAreHidden() {
        val file = myFixture.addFileToProject("selected.json", "{}").virtualFile
        assertFalse(updated(file).presentation.isEnabledAndVisible)
        assertFalse(updated(null).presentation.isEnabledAndVisible)
    }

    fun testDisabledIntegrationHidesImport() {
        val file = myFixture.addFileToProject("selected.ts", "let value=1;").virtualFile
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.DISABLED
        assertFalse(updated(file).presentation.isEnabledAndVisible)
    }

    private fun updated(file: VirtualFile?): AnActionEvent {
        val action = ActionManager.getInstance().getAction(ACTION_ID)
        assertNotNull("Code style import must be registered", action)
        assertEquals(ActionUpdateThread.BGT, action!!.actionUpdateThread)
        val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.PROJECT_VIEW_POPUP, DataContext { key ->
            when (key) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.VIRTUAL_FILE.name -> file
                else -> null
            }
        })
        val future = ApplicationManager.getApplication().executeOnPooledThread {
            ReadAction.run<RuntimeException> { action.update(event) }
        }
        PlatformTestUtil.waitWithEventsDispatching("Import action update did not finish", { future.isDone }, 10)
        future.get()
        return event
    }

    companion object {
        private const val ACTION_ID = "BiomeImportCodeStyle"
    }
}
