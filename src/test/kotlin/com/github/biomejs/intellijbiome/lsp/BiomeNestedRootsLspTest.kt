package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.icons.AllIcons
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lang.lsWidget.LanguageServicePopupSection
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerManagerListener
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.nio.file.Files

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeNestedRootsLspTest : BiomeLspFixtureTestCase() {
    private lateinit var parentFile: VirtualFile
    private lateinit var childFile: VirtualFile
    private val opened = CopyOnWriteArrayList<Pair<LspServer, VirtualFile>>()
    private val observedServers = CopyOnWriteArrayList<LspServer>()

    private lateinit var events: RuntimeGateEvents

    override fun setUp() {
        super.setUp()
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
        events = RuntimeGateEvents(project, testRootDisposable)
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        val root = myFixture.tempDirFixture.findOrCreateDir(".")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root)
        myFixture.tempDirFixture.findOrCreateDir("nested")
        TestNpmPackageInstaller(myFixture).installForTest(NestedBiome::class.java, root, "nested")
        myFixture.tempDirFixture.createFile("biome.json", """{"javascript":{"formatter":{"quoteStyle":"double"}}}""")
        myFixture.tempDirFixture.createFile("nested/biome.json", """{"root":true,"javascript":{"formatter":{"quoteStyle":"single"}}}""")
        parentFile = myFixture.tempDirFixture.createFile("parent.js", "const message='parent';\n")
        childFile = myFixture.tempDirFixture.createFile("nested/child.js", "const message=\"child\";\n")
        val parentExecutable = BiomePackage(project).binaryPath(parentFile)
        val childExecutable = BiomePackage(project).binaryPath(childFile)
        assertNotNull("Parent workspace executable must resolve", parentExecutable)
        assertNotNull("Child workspace executable must resolve", childExecutable)
        assertFalse("Each root must resolve its own executable", parentExecutable == childExecutable)
        LspServerManager.getInstance(project).addLspServerManagerListener(object : LspServerManagerListener {
            override fun serverStateChanged(lspServer: LspServer) {
                if (lspServer.providerClass == BiomeLspServerSupportProvider::class.java && lspServer !in observedServers) {
                    observedServers.add(lspServer)
                }
            }

            override fun fileOpened(lspServer: LspServer, file: VirtualFile) {
                if (lspServer.providerClass == BiomeLspServerSupportProvider::class.java) opened.add(lspServer to file)
            }
        }, testRootDisposable, true)
    }

    fun testParentThenChildServiceFormattingUsesChildServer() { exercise(parentFirst = true) }
    fun testChildThenParentServiceFormattingUsesChildServer() { exercise(parentFirst = false) }
    fun testParentThenChildIdeFormattingUsesChildServer() { exercise(parentFirst = true, throughIde = true) }
    fun testChildThenParentIdeFormattingUsesChildServer() { exercise(parentFirst = false, throughIde = true) }

    fun testParentFirstPublicRestartRestoresBothWorkspaces() {
        verifyPublicRestart(parentFirst = true)
    }

    fun testChildFirstPublicRestartRestoresBothWorkspaces() {
        verifyPublicRestart(parentFirst = false)
    }

    fun testChildWidgetRestartPreservesParentWorkspace() {
        verifyWidgetRestart(childFile)
    }

    fun testChildWidgetRestartRestoresAllOpenDocuments() {
        val additionalChild = myFixture.tempDirFixture.createFile("nested/additional.js", "const additional=\"child\";\n")
        verifyWidgetRestart(childFile, additionalChild)
    }

    fun testParentWidgetRestartPreservesChildWorkspace() {
        verifyWidgetRestart(parentFile)
    }

    fun testIndependentConfigCreationWaitsForExplicitRestart() {
        val config = childFile.parent.toNioPath().resolve("biome.json")
        Files.delete(config)
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        myFixture.configureFromExistingVirtualFile(parentFile)
        waitUntilFileOpenedByLspServer(project, parentFile, timeout = 20)
        awaitFirstServerReadiness(parentFile)
        myFixture.configureFromExistingVirtualFile(childFile)
        waitUntilFileOpenedByLspServer(project, childFile, timeout = 20)
        events.awaitDiagnostics(childFile)
        val original = servers().single()
        assertEquals(listOf(parentFile.parent), original.descriptor.roots.toList())
        val editors = FileEditorManager.getInstance(project).getEditors(childFile).toList()
        val document = FileDocumentManager.getInstance().getDocument(childFile)!!

        Files.writeString(config, """{"root":true,"javascript":{"formatter":{"quoteStyle":"single"}}}""")
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        settleLifecycle()
        assertEquals("Config creation must not replace existing workspace servers", listOf(original), servers().toList())
        formatExistingDocumentAndAssert(parentFile, "const message = \"parent\";\n")
        project.service<BiomeServerService>().restartBiomeServer()

        PlatformTestUtil.waitWithEventsDispatching("Explicit Restart did not acquire the independent child", {
            servers().any { server -> server.descriptor.roots.toList() == listOf(childFile.parent) &&
                opened.any { it.first === server && it.second == childFile } }
        }, 20)
        val child = servers().single { it.descriptor.roots.toList() == listOf(childFile.parent) }
        events.awaitDiagnostics(childFile, child)
        val parent = servers().single { it.descriptor.roots.toList() == listOf(parentFile.parent) }
        events.awaitDiagnostics(parentFile, parent)
        assertIndependentOwners(servers())
        formatExistingDocumentAndAssert(childFile, "const message = 'child';\n")
        assertEquals(editors, FileEditorManager.getInstance(project).getEditors(childFile).toList())
        assertSame(document, FileDocumentManager.getInstance().getDocument(childFile))
    }

    fun testMalformedChildRepairRetainsExistingEditorAndOwners() {
        exercise(parentFirst = true)
        val originals = servers().toList()
        val editors = FileEditorManager.getInstance(project).getEditors(childFile).toList()
        val config = childFile.parent.toNioPath().resolve("biome.json")
        val valid = Files.readString(config)
        Files.writeString(config, "{broken")
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        settleLifecycle()
        assertEquals("A temporary parse error must retain the established owners", originals, servers().toList())
        resetChildDocument()
        Files.writeString(config, valid)
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        settleLifecycle()
        assertEquals(originals, servers().toList())
        formatExistingDocumentAndAssert(childFile, "const message = 'child';\n")
        assertEquals(editors, FileEditorManager.getInstance(project).getEditors(childFile).toList())
    }

    fun testParentWidgetRestartDoesNotReviveDeliberatelyStoppedChild() {
        exercise(parentFirst = true)
        val originals = servers().toList()
        val parent = originals.single { it.descriptor.roots.toList() == listOf(parentFile.parent) }
        val child = originals.single { it !== parent }
        val editors = FileEditorManager.getInstance(project).getEditors(childFile).toList()
        resetChildDocument()
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(parentFile)!!.setText("const message='parent';\n")
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        invokeWidgetLifecycleAction(child, parentFile, LanguageServicePopupSection.Other)
        PlatformTestUtil.waitWithEventsDispatching("Widget Stop did not remove the child workspace", {
            servers().toList() == listOf(parent)
        }, 20)
        invokeWidgetLifecycleAction(parent, parentFile, LanguageServicePopupSection.ForCurrentFile)
        PlatformTestUtil.waitWithEventsDispatching("Widget Restart did not replace the parent", {
            servers().any { it !== parent && it.descriptor.roots.toList() == listOf(parentFile.parent) &&
                it.initializeResult?.serverInfo?.version == "2.5.15" }
        }, 20)
        val replacement = servers().single { it !== parent && it.descriptor.roots.toList() == listOf(parentFile.parent) }
        events.awaitDiagnostics(parentFile, replacement)
        formatExistingDocumentAndAssert(parentFile, "const message = \"parent\";\n")
        // Synthetic provider callbacks must not consume the explicit Stop as a real reopen.
        BiomeLspServerSupportProvider().fileOpened(project, childFile,
            object : com.intellij.platform.lsp.api.LspServerSupportProvider.LspServerStarter {
                override fun ensureServerStarted(descriptor: com.intellij.platform.lsp.api.LspServerDescriptor) {
                    LspServerManager.getInstance(project).ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
                }
            })
        settleLifecycle()
        assertEquals(BiomeServerService.Outcome.Unavailable,
            formatExistingDocumentAndAssert(childFile, "const message=\"child\";\n"))
        assertEquals("Restarting the parent must not revive the explicitly stopped child", 1, servers().size)
        assertSame(replacement, servers().single())
        assertEquals(listOf(parentFile.parent), replacement.descriptor.roots.toList())
        assertEquals(editors, FileEditorManager.getInstance(project).getEditors(childFile).toList())
        assertEquals("Only the selected parent may be restarted", (originals + replacement).toSet(), observedServers.toSet())
    }

    fun testReopeningDeliberatelyStoppedChildResumesOnlyChild() {
        exercise(parentFirst = true)
        val originals = servers().toList()
        val parent = originals.single { it.descriptor.roots.toList() == listOf(parentFile.parent) }
        val child = originals.single { it !== parent }
        invokeWidgetLifecycleAction(child, parentFile, LanguageServicePopupSection.Other)
        PlatformTestUtil.waitWithEventsDispatching("Widget Stop did not remove the child workspace", {
            servers().toList() == listOf(parent)
        }, 20)
        saveAndReopenChildEditor()
        PlatformTestUtil.waitWithEventsDispatching("A real file reopen did not resume the stopped child", {
            val current = servers()
            current.size == 2 && parent in current && child !in current &&
                current.any { server -> server.descriptor.roots.toList() == listOf(childFile.parent) &&
                    opened.any { it.first === server && it.second == childFile } }
        }, 20)
        val replacement = servers().single { it !== parent }
        events.awaitDiagnostics(childFile, replacement)
        assertIndependentOwners(servers())
        resetChildDocument()
        formatExistingDocumentAndAssert(childFile, "const message = 'child';\n")
    }

    fun testStoppingParentDoesNotDiscardQueuedChildWidgetRestart() {
        exercise(parentFirst = true)
        val dispatcher = QueuedDiscoveryDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        com.intellij.openapi.util.Disposer.register(testRootDisposable) {
            scope.cancel()
            dispatcher.drainCancelledTasks()
        }
        project.replaceService(BiomeWidgetRestartService::class.java,
            BiomeWidgetRestartService(project, scope), testRootDisposable)
        val parent = servers().single { it.descriptor.roots.toList() == listOf(parentFile.parent) }
        val child = servers().single { it.descriptor.roots.toList() == listOf(childFile.parent) }
        val editors = FileEditorManager.getInstance(project).getEditors(childFile).toList()
        invokeWidgetLifecycleAction(child, childFile, LanguageServicePopupSection.ForCurrentFile)
        // Let the SDK's native scan finish while the plugin's fallback remains held.
        // The surviving parent can hide the child from that native scan.
        settleLifecycle()
        invokeWidgetLifecycleAction(parent, childFile, LanguageServicePopupSection.Other)

        PlatformTestUtil.waitWithEventsDispatching("Stopping the parent discarded the independent child's queued Restart", {
            dispatcher.runNextPendingTask()
            val current = servers()
            current.size == 1 && current.single() !== child &&
                current.single().descriptor.roots.toList() == listOf(childFile.parent) &&
                opened.any { it.first === current.single() && it.second == childFile }
        }, 20)
        val replacement = servers().single()
        events.awaitDiagnostics(childFile, replacement)
        resetChildDocument()
        formatExistingDocumentAndAssert(childFile, "const message = 'child';\n")
        assertEquals(editors, FileEditorManager.getInstance(project).getEditors(childFile).toList())
        assertFalse("Restarting the child must not resume its deliberately stopped parent",
            servers().any { parentFile.parent in it.descriptor.roots })
    }

    fun testReopeningChildDoesNotResumeStoppedParentDuringChildRestart() = verifyReopeningStoppedChild(globalStop = false)

    fun testReopeningChildAfterGlobalStopKeepsParentStopped() = verifyReopeningStoppedChild(globalStop = true)

    private fun verifyReopeningStoppedChild(globalStop: Boolean) {
        exercise(parentFirst = true)
        val parent = servers().single { it.descriptor.roots.toList() == listOf(parentFile.parent) }
        val child = servers().single { it.descriptor.roots.toList() == listOf(childFile.parent) }
        if (globalStop) project.service<BiomeServerService>().stopBiomeServer()
        else {
            invokeWidgetLifecycleAction(child, parentFile, LanguageServicePopupSection.Other)
            invokeWidgetLifecycleAction(parent, childFile, LanguageServicePopupSection.Other)
        }
        PlatformTestUtil.waitWithEventsDispatching("Both selected roots must stop", { servers().isEmpty() }, 20)
        saveAndReopenChildEditor()
        PlatformTestUtil.waitWithEventsDispatching("Reopening the child did not resume its own workspace", {
            val current = servers()
            current.size == 1 && current.single().descriptor.roots.toList() == listOf(childFile.parent) &&
                opened.any { it.first === current.single() && it.second == childFile }
        }, 20)
        val reopened = servers().single()
        events.awaitDiagnostics(childFile, reopened)
        val parentEditors = FileEditorManager.getInstance(project).getEditors(parentFile).toList()
        invokeWidgetLifecycleAction(reopened, childFile, LanguageServicePopupSection.ForCurrentFile)
        PlatformTestUtil.waitWithEventsDispatching("Child Restart did not restore the reopened child", {
            servers().any { server -> server !== reopened &&
                server.descriptor.roots.toList() == listOf(childFile.parent) &&
                opened.any { it.first === server && it.second == childFile } }
        }, 20)
        settleLifecycle()
        assertEquals("Reopening an independent child must not clear its parent's Stop intent",
            listOf(childFile.parent), servers().flatMap { it.descriptor.roots.toList() })
        val replacement = servers().single()
        events.awaitDiagnostics(childFile, replacement)
        resetChildDocument()
        formatExistingDocumentAndAssert(childFile, "const message = 'child';\n")
        assertEquals(parentEditors, FileEditorManager.getInstance(project).getEditors(parentFile).toList())
    }

    private fun saveAndReopenChildEditor() {
        val documents = FileDocumentManager.getInstance()
        documents.saveDocument(requireNotNull(documents.getDocument(childFile)))
        val editors = FileEditorManager.getInstance(project)
        editors.closeFile(childFile)
        assertFalse(editors.isFileOpen(childFile))
        // The SDK test manager omits Before; mirror the desktop editor's public event order.
        project.messageBus.syncPublisher(com.intellij.openapi.fileEditor.FileEditorManagerListener.Before.FILE_EDITOR_MANAGER)
            .beforeFileOpened(editors, childFile)
        myFixture.configureFromExistingVirtualFile(childFile)
    }

    private fun resetChildDocument() {
        val document = FileDocumentManager.getInstance().getDocument(childFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("const message=\"child\";\n") }
        FileDocumentManager.getInstance().saveDocument(document)
    }

    private fun servers() = LspServerManager.getInstance(project)
        .getServersForProvider(BiomeLspServerSupportProvider::class.java)

    private fun settleLifecycle() {
        val deadline = System.nanoTime() + 2_000_000_000L
        PlatformTestUtil.waitWithEventsDispatching("Lifecycle tasks did not settle", { System.nanoTime() >= deadline }, 5)
    }

    private fun invokeWidgetLifecycleAction(server: LspServer, currentFile: VirtualFile,
        location: LanguageServicePopupSection) {
        val widget = BiomeLspServerSupportProvider().createLspServerWidgetItem(server, currentFile)
        assertEquals(location, widget.widgetActionLocation)
        val row = widget.createWidgetAction()
        val actions = row.templatePresentation.getClientProperty(ActionUtil.INLINE_ACTIONS)
            ?: (row as ActionGroup).getChildren(null).toList()
        val action = actions.single { it.templatePresentation.icon == AllIcons.Actions.StopAndRestart }
        action.actionPerformed(AnActionEvent.createFromAnAction(action, null, ActionPlaces.STATUS_BAR_PLACE,
            DataContext { key ->
                when (key) {
                    CommonDataKeys.PROJECT.name -> project
                    CommonDataKeys.VIRTUAL_FILE.name -> currentFile
                    else -> null
                }
            }))
    }

    private fun verifyWidgetRestart(file: VirtualFile, additionalChild: VirtualFile? = null) {
        exercise(parentFirst = true)
        if (additionalChild != null) {
            myFixture.configureFromExistingVirtualFile(additionalChild)
            waitUntilFileOpenedByLspServer(project, additionalChild, timeout = 20)
            events.awaitDiagnostics(additionalChild)
        }
        val openFiles = listOfNotNull(parentFile, childFile, additionalChild)
        val manager = LspServerManager.getInstance(project)
        val original = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList()
        val selected = original.single { it.descriptor.roots.toList() == listOf(file.parent) }
        val unaffected = original.single { it !== selected }
        val editorManager = FileEditorManager.getInstance(project)
        val editors = openFiles.associateWith { editorManager.getEditors(it).toList() }
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(parentFile)!!.setText("const message='parent';\n")
            FileDocumentManager.getInstance().getDocument(childFile)!!.setText("const message=\"child\";\n")
        }
        FileDocumentManager.getInstance().saveAllDocuments()

        val widget = BiomeLspServerSupportProvider().createLspServerWidgetItem(selected, file)
        assertEquals(LanguageServicePopupSection.ForCurrentFile, widget.widgetActionLocation)
        val row = widget.createWidgetAction()
        // The public widget exposes inline actions in the new UI and group children in the old UI.
        val actions = row.templatePresentation.getClientProperty(ActionUtil.INLINE_ACTIONS)
            ?: (row as ActionGroup).getChildren(null).toList()
        val restart = actions.single { it.templatePresentation.icon == AllIcons.Actions.StopAndRestart }
        val event = AnActionEvent.createFromAnAction(restart, null, ActionPlaces.STATUS_BAR_PLACE, DataContext { key ->
            when (key) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.VIRTUAL_FILE.name -> file
                else -> null
            }
        })
        restart.actionPerformed(event)

        PlatformTestUtil.waitWithEventsDispatching("Widget Restart did not restore the selected workspace", {
            val current = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
            current.size == 2 && unaffected in current && selected !in current &&
                current.all { it.initializeResult?.serverInfo?.version == "2.5.15" }
        }, 20)
        val current = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList()
        val replacement = current.single { it !== unaffected }
        events.awaitDiagnostics(file, replacement)
        if (additionalChild != null) {
            events.awaitDiagnostics(additionalChild, replacement)
            formatExistingDocumentAndAssert(additionalChild, "const additional = 'child';\n")
        }
        assertTrue("The replacement must open the existing document",
            opened.any { it.first === replacement && it.second == file })
        assertIndependentOwners(current)
        formatExistingDocumentAndAssert(childFile, "const message = 'child';\n")
        formatExistingDocumentAndAssert(parentFile, "const message = \"parent\";\n")
        assertEquals("Widget Restart must preserve all editor instances", editors,
            openFiles.associateWith { editorManager.getEditors(it).toList() })
        assertEquals("Widget Restart must replace only the selected server",
            (original + replacement).toSet(), observedServers.toSet())
    }

    private fun verifyPublicRestart(parentFirst: Boolean) {
        exercise(parentFirst)
        val manager = LspServerManager.getInstance(project)
        val editorManager = FileEditorManager.getInstance(project)
        // The SDK's test editor manager returns HashMap key order, not tab order.
        // Choose real fixture files until its public openFiles array has the desired order;
        // all setup reopens finish before capturing identities or measuring Restart.
        FileDocumentManager.getInstance().saveAllDocuments()
        var ordered = false
        for (attempt in 0 until 32) {
            editorManager.closeFile(parentFile)
            editorManager.closeFile(childFile)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (attempt > 0) childFile = myFixture.tempDirFixture.createFile(
                "nested/restart-child-$attempt.js", "const message=\"child\";\n")
            for (file in if (parentFirst) listOf(parentFile, childFile) else listOf(childFile, parentFile)) {
                editorManager.openFile(file, true)
                waitUntilFileOpenedByLspServer(project, file, timeout = 20)
            }
            val order = editorManager.openFiles.toList()
            ordered = (order.indexOf(parentFile) < order.indexOf(childFile)) == parentFirst
            if (ordered) {
                println("Restart open-file order: " + order.map { it.path })
                break
            }
        }
        assertTrue("SDK test editor manager must expose the requested restart order", ordered)
        events.awaitDiagnostics(childFile)
        val original = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList()
        val childEditors = editorManager.getEditors(childFile).toList()
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(parentFile)!!.setText("const message='parent';\n")
            FileDocumentManager.getInstance().getDocument(childFile)!!.setText("const message=\"child\";\n")
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        val freshDiagnostics = CopyOnWriteArrayList<Pair<LspServer, VirtualFile>>()
        manager.addLspServerManagerListener(object : LspServerManagerListener {
            override fun diagnosticsReceived(lspServer: LspServer, file: VirtualFile) {
                if (lspServer.providerClass == BiomeLspServerSupportProvider::class.java) {
                    freshDiagnostics.add(lspServer to file)
                }
            }
        }, testRootDisposable, false)
        project.service<BiomeServerService>().restartBiomeServer()
        PlatformTestUtil.waitWithEventsDispatching("Public Restart did not restore both independent workspaces", {
            val current = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
            current.size == 2 && current.none { it in original } &&
                current.all { it.initializeResult?.serverInfo?.version != null }
        }, 20)
        val restarted = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList()
        assertIndependentOwners(restarted)
        assertEquals("Restart must retain the child's editor instances", childEditors, editorManager.getEditors(childFile).toList())
        for (file in listOf(parentFile, childFile)) {
            PlatformTestUtil.waitWithEventsDispatching("Replacement server did not open and diagnose ${file.path}", {
                restarted.any { server -> opened.any { it.first === server && it.second == file } &&
                    freshDiagnostics.any { it.first === server && it.second == file } }
            }, 20)
        }
        assertEquals(setOf(childFile.parent), opened.filter { it.first in restarted && it.second == childFile }
            .flatMap { it.first.descriptor.roots.toList() }.toSet())
        formatExistingDocumentAndAssert(childFile, "const message = 'child';\n")
        formatExistingDocumentAndAssert(parentFile, "const message = \"parent\";\n")
        assertEquals(childEditors, editorManager.getEditors(childFile).toList())
        assertEquals("Only the two replacement servers may be started", (original + restarted).toSet(), observedServers.toSet())
    }

    fun testMalformedExistingRootDoesNotPermanentlyRejectNewFile() {
        verifyEstablishedRootOwnership(removeConfig = false, nonRootChild = false)
    }

    fun testMissingExistingRootRetainsNonRootChildOwnership() {
        verifyEstablishedRootOwnership(removeConfig = true, nonRootChild = true)
    }

    private fun verifyEstablishedRootOwnership(removeConfig: Boolean, nonRootChild: Boolean) {
        myFixture.configureFromExistingVirtualFile(parentFile)
        waitUntilFileOpenedByLspServer(project, parentFile, timeout = 20)
        awaitFirstServerReadiness(parentFile)
        val original = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
        val rootConfig = parentFile.parent.toNioPath().resolve("biome.json")
        val validConfig = Files.readString(rootConfig)
        if (removeConfig) Files.delete(rootConfig) else Files.writeString(rootConfig, "{broken")
        VfsUtil.markDirtyAndRefresh(false, true, true, parentFile.parent)
        val latePath = if (nonRootChild) {
            myFixture.tempDirFixture.createFile("non-root/biome.json", """{"root":false,"extends":"//"}""")
            "non-root/late.js"
        } else "late.js"
        val lateFile = myFixture.tempDirFixture.createFile(latePath, "const message='late';\n")
        myFixture.configureFromExistingVirtualFile(lateFile)
        waitUntilFileOpenedByLspServer(project, lateFile, timeout = 15)
        Files.writeString(rootConfig, validConfig)
        VfsUtil.markDirtyAndRefresh(false, true, true, parentFile.parent)
        FileEditorManager.getInstance(project).closeFile(lateFile)
        myFixture.configureFromExistingVirtualFile(lateFile)
        waitUntilFileOpenedByLspServer(project, lateFile, timeout = 15)
        events.awaitDiagnostics(lateFile)
        formatAndAssert(lateFile, "const message = \"late\";\n")
        assertSame(original, LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java).single())
    }

    fun testMalformedEstablishedChildKeepsExclusiveOwnership() {
        exercise(parentFirst = true)
        val config = childFile.parent.toNioPath().resolve("biome.json")
        val validConfig = Files.readString(config)
        Files.writeString(config, "{broken")
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        val lateFile = myFixture.tempDirFixture.createFile("nested/late.js", "const message=\"late\";\n")
        myFixture.configureFromExistingVirtualFile(lateFile)
        waitUntilFileOpenedByLspServer(project, lateFile, timeout = 15)
        val owners = LspServerManager.getInstance(project)
            .getServersForProvider(BiomeLspServerSupportProvider::class.java)
            .filter { it.descriptor.isSupportedFile(lateFile) }
        assertEquals(setOf(childFile.parent), owners.flatMap { it.descriptor.roots.toList() }.toSet())
        assertEquals("Temporary child config errors must not transfer ownership to its parent",
            setOf(childFile.parent), opened.filter { it.second == lateFile }.flatMap { it.first.descriptor.roots.toList() }.toSet())
        Files.writeString(config, validConfig)
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        FileEditorManager.getInstance(project).closeFile(lateFile)
        myFixture.configureFromExistingVirtualFile(lateFile)
        waitUntilFileOpenedByLspServer(project, lateFile, timeout = 15)
        events.awaitDiagnostics(lateFile)
        formatAndAssert(lateFile, "const message = 'late';\n")
    }

    fun testExplicitManualConfigRetainsProjectWideOwnership() {
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            configPath = parentFile.parent.path
            executablePath = parentFile.parent.toNioPath().resolve("node_modules/.bin/biome").toString()
        }
        myFixture.configureFromExistingVirtualFile(childFile)
        waitUntilFileOpenedByLspServer(project, childFile, timeout = 20)
        val servers = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
        assertEquals(1, servers.size)
        assertEquals(listOf(parentFile.parent), servers.single().descriptor.roots.toList())
        assertTrue(servers.single().descriptor.isSupportedFile(parentFile))
        assertTrue(servers.single().descriptor.isSupportedFile(childFile))
        verifySingleWorkspaceRestart("const message='child';\n", "const message = \"child\";\n")
    }

    fun testNestedNonRootConfigRemainsInParentWorkspace() {
        Files.writeString(childFile.parent.toNioPath().resolve("biome.json"),
            """{"root":false,"javascript":{"formatter":{"quoteStyle":"single"}}}""")
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        myFixture.configureFromExistingVirtualFile(childFile)
        waitUntilFileOpenedByLspServer(project, childFile, timeout = 20)
        events.awaitDiagnostics(childFile)
        formatAndAssert(childFile, "const message = 'child';\n")
        val servers = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
        assertEquals(1, servers.size)
        assertEquals(listOf(parentFile.parent), servers.single().descriptor.roots.toList())
        assertEquals(setOf(parentFile.parent), opened.filter { it.second == childFile }.flatMap { it.first.descriptor.roots.toList() }.toSet())
        verifySingleWorkspaceRestart("const message=\"child\";\n", "const message = 'child';\n")
    }

    private fun verifySingleWorkspaceRestart(input: String, expected: String) {
        val manager = LspServerManager.getInstance(project)
        val original = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
        val editorManager = FileEditorManager.getInstance(project)
        val editors = editorManager.getEditors(childFile).toList()
        val document = FileDocumentManager.getInstance().getDocument(childFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(input) }
        FileDocumentManager.getInstance().saveDocument(document)
        val diagnosed = CopyOnWriteArrayList<LspServer>()
        manager.addLspServerManagerListener(object : LspServerManagerListener {
            override fun diagnosticsReceived(lspServer: LspServer, file: VirtualFile) {
                if (file == childFile && lspServer !== original) diagnosed.add(lspServer)
            }
        }, testRootDisposable, false)
        project.service<BiomeServerService>().restartBiomeServer()
        PlatformTestUtil.waitWithEventsDispatching("Single workspace restart did not restore the existing editor", {
            val current = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
            current.size == 1 && current.single() !== original && current.single() in diagnosed
        }, 20)
        val restarted = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
        assertEquals(listOf(parentFile.parent), restarted.descriptor.roots.toList())
        assertEquals(editors, editorManager.getEditors(childFile).toList())
        formatExistingDocumentAndAssert(childFile, expected)
        assertEquals(editors, editorManager.getEditors(childFile).toList())
        assertEquals(setOf(original, restarted), observedServers.toSet())
    }

    private fun exercise(parentFirst: Boolean, throughIde: Boolean = false) {
        val order = if (parentFirst) listOf(parentFile, childFile) else listOf(childFile, parentFile)
        order.forEachIndexed { index, file ->
            myFixture.configureFromExistingVirtualFile(file)
            waitUntilFileOpenedByLspServer(project, file, timeout = 20)
            if (index == 0) {
                // Stabilize only the first server, before opening the other root. Never reopen
                // the second file to force discovery or change its measured ownership.
                awaitFirstServerReadiness(file)
            } else {
                events.awaitDiagnostics(file)
            }
        }
        val servers = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
        println("Nested ownership: " + servers.joinToString { "${it.descriptor.roots.toList()} version=${it.initializeResult?.serverInfo?.version}" })
        println("Documents opened: " + opened.joinToString { "${it.second.path} -> ${it.first.descriptor.roots.toList()}" })
        formatAndAssert(childFile, "const message = 'child';\n", throughIde)
        formatAndAssert(parentFile, "const message = \"parent\";\n", throughIde)
        assertIndependentOwners(servers)
        assertEquals("Nested document must belong to its own root", setOf(childFile.parent),
            opened.filter { it.second == childFile }.flatMap { it.first.descriptor.roots.toList() }.toSet())
    }

    private fun assertIndependentOwners(servers: Collection<LspServer>) {
        assertEquals("Both independent roots must have a server", 2, servers.size)
        assertEquals(setOf(parentFile.parent, childFile.parent),
            servers.flatMap { it.descriptor.roots.toList() }.toSet())
        // Both proxies use the same Biome release and can share its daemon. Root ownership
        // and different quote styles must distinguish their behavior without version isolation.
        assertEquals(setOf("2.5.15"), servers.map { it.initializeResult?.serverInfo?.version }.toSet())
        for (file in listOf(parentFile, childFile)) {
            val owners = servers.filter { it.descriptor.isSupportedFile(file) }
            assertEquals("Exactly one server must own ${file.path}", 1, owners.size)
            assertEquals(listOf(file.parent), owners.single().descriptor.roots.toList())
        }
    }

    private fun awaitFirstServerReadiness(file: VirtualFile) {
        try {
            events.awaitDiagnostics(file, timeout = 5)
        } catch (_: AssertionError) {
            println("Initial readiness control: reopening first file ${file.path}")
            FileEditorManager.getInstance(project).closeFile(file)
            myFixture.configureFromExistingVirtualFile(file)
            waitUntilFileOpenedByLspServer(project, file, timeout = 20)
            events.awaitDiagnostics(file)
        }
    }

    private fun formatAndAssert(file: VirtualFile, expected: String, throughIde: Boolean = false) {
        val editors = FileEditorManager.getInstance(project).getEditors(file).toList()
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val modificationStamp = document.modificationStamp
        myFixture.openFileInEditor(file)
        assertEquals(editors, FileEditorManager.getInstance(project).getEditors(file).toList())
        assertSame(document, myFixture.editor.document)
        assertEquals(modificationStamp, document.modificationStamp)
        if (throughIde) {
            BiomeSettings.getInstance(project).enableLspFormat = true
            myFixture.performEditorAction("ReformatCode")
            PlatformTestUtil.waitWithEventsDispatching("IDE formatting did not produce expected complete output", { myFixture.editor.document.text == expected }, 20)
            assertEquals(expected, myFixture.editor.document.text)
            return
        }
        formatExistingDocumentAndAssert(file, expected)
    }

    private fun formatExistingDocumentAndAssert(file: VirtualFile, expected: String): BiomeServerService.Outcome {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val operation = scope.async { withTimeout(15_000) { project.service<BiomeServerService>().format(document) } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Existing editor formatting timed out", { operation.isCompleted }, 20)
            val outcome = runBlocking { operation.await() }
            assertEquals(expected, document.text)
            return outcome
        } finally { scope.cancel() }
    }

    @TestNpmPackage("@biomejs/biome@2.5.15")
    private class NestedBiome
}
