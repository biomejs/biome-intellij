package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import java.util.concurrent.CopyOnWriteArrayList
import java.nio.file.Files

class BiomeOpenConfigurationActionTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private val notifications = CopyOnWriteArrayList<Notification>()
    private val settings get() = BiomeSettings.getInstance(project)

    override fun setUp() {
        super.setUp()
        settings.configurationMode = ConfigurationMode.AUTOMATIC
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "Biome") notifications += notification
            }
        })
    }

    fun testNavigationActionIsRegistered() {
        assertNotNull("Configuration navigation must be available in Find Action",
            ActionManager.getInstance().getAction(ACTION_ID))
        val manager = ActionManager.getInstance()
        for (menu in listOf("EditorPopupMenu", "ProjectViewPopupMenu")) {
            val group = manager.getAction(menu) as ActionGroup
            assertTrue("Navigation must be available from $menu",
                group.getChildren(null).any { manager.getId(it) == ACTION_ID })
        }
    }

    fun testProjectViewSelectionWinsOverUnrelatedEditor() {
        val editorConfig = myFixture.tempDirFixture.createFile("editor/biome.json", "{}")
        val editorSource = myFixture.tempDirFixture.createFile("editor/file.js", "")
        val selectedConfig = myFixture.tempDirFixture.createFile("selected/biome.jsonc", "{broken")
        val selectedSource = myFixture.tempDirFixture.createFile("selected/file.js", "")
        myFixture.configureFromExistingVirtualFile(editorSource)
        perform(selectedSource, myFixture.editor)
        assertOpened(selectedConfig)
        assertFalse(FileEditorManager.getInstance(project).isFileOpen(editorConfig))
    }

    fun testEditorContextOpensNearestConfigurationWithoutServer() {
        val config = myFixture.tempDirFixture.createFile("biome.json", "{}")
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        myFixture.configureFromExistingVirtualFile(source)
        assertTrue(servers().isEmpty())
        perform(null, myFixture.editor)
        assertOpened(config)
        assertTrue("Navigation must not explicitly launch a server", servers().isEmpty())
    }

    fun testReadOnlyDocumentAndViewerAllowNavigation() {
        val config = myFixture.tempDirFixture.createFile("biome.json", "{}")
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(source))
        document.setReadOnly(true)
        WriteCommandAction.runWriteCommandAction(project) { source.isWritable = false }
        val viewer = EditorFactory.getInstance().createViewer(document, project)
        try {
            perform(source)
            assertOpened(config)
            FileEditorManager.getInstance(project).closeFile(config)
            perform(null, viewer)
            assertOpened(config)
        } finally {
            EditorFactory.getInstance().releaseEditor(viewer)
            WriteCommandAction.runWriteCommandAction(project) { source.isWritable = true }
            document.setReadOnly(false)
        }
    }

    fun testMissingConfigurationReportsWithoutCreatingFile() {
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        perform(source)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, notifications.size)
        assertTrue(FileEditorManager.getInstance(project).openFiles.isEmpty())
        assertNull(source.parent.findChild("biome.json"))
        assertNull(source.parent.findChild("biome.jsonc"))
        assertTrue(servers().isEmpty())
    }

    fun testDisabledModeDisablesNavigationAndDoesNothing() {
        val config = myFixture.tempDirFixture.createFile("biome.json", "{}")
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        settings.configurationMode = ConfigurationMode.DISABLED
        val (action, event) = updated(source)
        assertFalse(event.presentation.isEnabled)
        action.actionPerformed(event)
        assertFalse(FileEditorManager.getInstance(project).isFileOpen(config))
        assertTrue(notifications.isEmpty())
    }

    fun testSettingsChangeBetweenUpdateAndInvocationUsesNewOverride() {
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        val first = myFixture.tempDirFixture.createFile("first.json", "{}")
        val second = myFixture.tempDirFixture.createFile("second.jsonc", "{}")
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.configPath = first.path
        val (action, event) = updated(source)
        settings.configPath = second.path
        action.actionPerformed(event)
        assertOpened(second)
        assertFalse(FileEditorManager.getInstance(project).isFileOpen(first))
    }

    fun testDeletedConfigurationReportsWithoutRecreatingIt() {
        val config = myFixture.tempDirFixture.createFile("biome.json", "{}")
        val source = myFixture.tempDirFixture.createFile("file.js", "")
        val (action, event) = updated(source)
        WriteCommandAction.runWriteCommandAction(project) { config.delete(this) }
        action.actionPerformed(event)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, notifications.size)
        assertNull(source.parent.findChild("biome.json"))
        assertTrue(FileEditorManager.getInstance(project).openFiles.isEmpty())
    }

    fun testAmbiguousFindActionReportsWithoutSelectingAProjectRoot() {
        myFixture.tempDirFixture.createFile("biome.json", "{}")
        val directory = Files.createTempDirectory("biome-navigation-second-root-")
        Disposer.register(testRootDisposable) { FileUtil.delete(directory.toFile()) }
        Files.writeString(directory.resolve("biome.json"), "{}")
        val base = checkNotNull(VfsUtil.findFile(directory, true))
        ModuleRootModificationUtil.addContentRoot(myFixture.module, base.path)
        perform(null)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, notifications.size)
        assertTrue(FileEditorManager.getInstance(project).openFiles.isEmpty())
    }

    fun testBoundWidgetContextWinsOverAnUnrelatedEditor() {
        val config = myFixture.tempDirFixture.createFile("widget/biome.json", "{}")
        val unrelated = myFixture.tempDirFixture.createFile("editor/file.js", "")
        myFixture.configureFromExistingVirtualFile(unrelated)
        val action = BiomeOpenConfigurationAction(config.parent)
        val (_, event) = updated(unrelated, myFixture.editor)
        action.actionPerformed(event)
        assertOpened(config)
    }

    fun testFindActionWithoutFileUsesSingleProjectBase() {
        val config = myFixture.tempDirFixture.createFile("biome.json", "{}")
        perform(null)
        assertOpened(config)
    }

    private fun perform(file: VirtualFile?, editor: Editor? = null) {
        val (action, event) = updated(file, editor)
        assertTrue("Navigation must be available", event.presentation.isEnabledAndVisible)
        action.actionPerformed(event)
    }

    private fun assertOpened(config: VirtualFile) {
        assertTrue("Expected ${config.path} to open", FileEditorManager.getInstance(project).isFileOpen(config))
    }

    private fun servers() = LspServerManager.getInstance(project)
        .getServersForProvider(BiomeLspServerSupportProvider::class.java)

    private fun updated(file: VirtualFile?, editor: Editor? = null): Pair<AnAction, AnActionEvent> {
        val action = checkNotNull(ActionManager.getInstance().getAction(ACTION_ID)) { "Navigation action is missing" }
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
        PlatformTestUtil.waitWithEventsDispatching("Navigation update did not finish", { future.isDone }, 10)
        future.get()
        return action to event
    }

    companion object {
        private const val ACTION_ID = "BiomeOpenConfiguration"
    }
}
