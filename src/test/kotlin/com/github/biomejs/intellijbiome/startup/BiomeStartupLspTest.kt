package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.fixtures.StartupLspProcess
import com.github.biomejs.intellijbiome.lsp.BiomeLspLifecycleService
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.lsp.waitUntilFileOpenedByLspServer
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.BaseProjectDirectories
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.testFramework.replaceService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.lsp4j.ConfigurationItem
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BiomeStartupLspTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private lateinit var invocationLog: Path
    private lateinit var configurationLog: Path
    private lateinit var executable: Path
    private lateinit var peerArguments: List<String>

    override fun setUp() {
        super.setUp()
        invocationLog = Path.of(myFixture.tempDirPath, "invocations.log")
        configurationLog = Path.of(myFixture.tempDirPath, "configuration.json")
        executable = Path.of(myFixture.tempDirPath, "lsp-only-launcher")
        val fixtureClasses = Path.of(myFixture.tempDirPath, "fixture-classes")
        val fixtureName = StartupLspProcess::class.java.name.replace('.', '/') + ".class"
        val fixtureClass = fixtureClasses.resolve(fixtureName)
        Files.createDirectories(fixtureClass.parent)
        StartupLspProcess::class.java.classLoader.getResourceAsStream(fixtureName)!!.use {
            Files.copy(it, fixtureClass, StandardCopyOption.REPLACE_EXISTING)
        }
        peerArguments = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", fixtureClasses.toString(), StartupLspProcess::class.java.name)
        writeShellLauncher()
        myFixture.addFileToProject("biome.json", """{"root":true}""")
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = executable.toString()
            configPath = ""
        }
    }

    fun testManualStartupLaunchesLspWithoutVersionProbe() {
        val descriptor = descriptorFor(myFixture.addFileToProject("index.js", "let value = 1;").virtualFile)
        assertFalse("Selecting a server must not execute a version probe", Files.exists(invocationLog))
        start(descriptor) { assertOnlyLspInvocation(Path.of(myFixture.tempDirPath)) }
    }

    fun testAutomaticStartupLaunchesLspWithoutVersionProbe() {
        configureNodeInterpreter()
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.AUTOMATIC
        myFixture.addFileToProject("package.json", """{"name":"fixture","dependencies":{"@biomejs/biome":"2.5.15"}}""")
        myFixture.addFileToProject("node_modules/@biomejs/biome/package.json",
            """{"name":"@biomejs/biome","version":"2.5.15","bin":{"biome":"bin/biome"}}""")
        myFixture.addFileToProject("node_modules/@biomejs/biome/bin/biome", nodeLauncher())
        val descriptor = descriptorFor(myFixture.addFileToProject("index.js", "let value = 1;").virtualFile)
        assertFalse("Automatic selection must not execute a version probe", Files.exists(invocationLog))
        start(descriptor) { assertOnlyLspInvocation(Path.of(myFixture.tempDirPath)) }
    }

    fun testAutomaticStartupSurvivesDiscoveryIndicatorCancellation() {
        configureNodeInterpreter()
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.AUTOMATIC
        myFixture.addFileToProject("package.json", """{"name":"fixture","dependencies":{"@biomejs/biome":"2.5.15"}}""")
        myFixture.addFileToProject("node_modules/@biomejs/biome/package.json",
            """{"name":"@biomejs/biome","version":"2.5.15","bin":{"biome":"bin/biome"}}""")
        myFixture.addFileToProject("node_modules/@biomejs/biome/bin/biome", nodeLauncher())
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        val discoveryIndicator = EmptyProgressIndicator()
        val descriptor = ProgressManager.getInstance().runProcess(Computable { descriptorFor(file) }, discoveryIndicator)
        discoveryIndicator.cancel()

        // Discovery has finished; its cancellation must not poison the queued Node launch.
        start(descriptor) { assertOnlyLspInvocation(Path.of(myFixture.tempDirPath)) }
    }

    fun testProjectStartupDiscoversRestoredChildWithoutRestartingIndependentWorkspace() {
        writeShellLauncher(mapOf("BIOME_EDITOR_FIXTURE_DIRECTORY" to myFixture.tempDirPath))
        myFixture.addFileToProject("independent/biome.json", """{"root":true}""")
        val independent = myFixture.addFileToProject("independent/index.js", "let independent = 1;").virtualFile
        myFixture.addFileToProject("child/biome.json", """{"root":false,"extends":"//"}""")
        val child = myFixture.addFileToProject("child/index.js", "let child = 1;").virtualFile
        val manager = LspServerManager.getInstance(project)
        val editorManager = FileEditorManager.getInstance(project)
        editorManager.openFile(independent, false)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Independent server did not initialize", {
                manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
                    .singleOrNull()?.state == LspServerState.Running
            }, 8)
            val independentServer = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
            val directories = DelayedBaseDirectories(project, project.getBaseDirectories())
            project.replaceService(BaseProjectDirectories::class.java, directories, testRootDisposable)
            editorManager.openFile(child, true)
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            assertTrue("Restored editor must attempt discovery before bases are ready", directories.unavailableReads.get() > 0)
            val childEditors = editorManager.getEditors(child).toList()
            assertFalse(independentServer.descriptor.isSupportedFile(child))
            assertEquals(listOf(independentServer), manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList())

            // Model the base-directory snapshot becoming ready after the early editor-open notification.
            directories.available = true
            runProjectStartupActivities()
            PlatformTestUtil.waitWithEventsDispatching("Project startup did not discover the restored child workspace", {
                val servers = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
                servers.size == 2 && servers.all { it.state == LspServerState.Running }
            }, 8)
            waitUntilFileOpenedByLspServer(project, child)
            val servers = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
            assertTrue("The independent server must survive startup discovery", servers.any { it === independentServer })
            assertEquals(listOf(child.parent.parent), servers.single { it.descriptor.isSupportedFile(child) }.descriptor.roots.toList())
            assertEquals("Recovery must not reopen the editor", childEditors, editorManager.getEditors(child).toList())
            assertEquals(2, invocations().size)
            assertTrue("Recovery must not run a version probe", invocations().all { it.substringAfter('\t') == "lsp-proxy" })
        } finally {
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
        }
    }

    fun testProjectStartupKeepsStoppedWorkspaceStoppedWhileDiscoveringAnotherRoot() {
        val parent = myFixture.addFileToProject("index.js", "let parent = 1;").virtualFile
        myFixture.addFileToProject("independent/biome.json", """{"root":true}""")
        val independent = myFixture.addFileToProject("independent/index.js", "let independent = 1;").virtualFile
        val manager = LspServerManager.getInstance(project)
        val editorManager = FileEditorManager.getInstance(project)
        editorManager.openFile(parent, false)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Parent server did not initialize", {
                manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
                    .singleOrNull()?.state == LspServerState.Running
            }, 8)
            project.service<BiomeServerService>().stopBiomeServer()
            PlatformTestUtil.waitWithEventsDispatching("Stopped parent was not removed", {
                manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).isEmpty()
            }, 8)
            val directories = DelayedBaseDirectories(project, project.getBaseDirectories())
            project.replaceService(BaseProjectDirectories::class.java, directories, testRootDisposable)
            // The SDK test manager omits Before; preserve the desktop's real-open intent.
            project.messageBus.syncPublisher(com.intellij.openapi.fileEditor.FileEditorManagerListener.Before.FILE_EDITOR_MANAGER)
                .beforeFileOpened(editorManager, independent)
            editorManager.openFile(independent, true)
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            assertTrue(directories.unavailableReads.get() > 0)

            directories.available = true
            runProjectStartupActivities()
            PlatformTestUtil.waitWithEventsDispatching("Startup did not discover the unstopped independent root", {
                manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).singleOrNull()?.let {
                    it.state == LspServerState.Running && it.descriptor.roots.toList() == listOf(independent.parent)
                } == true
            }, 8)
            assertTrue(project.service<BiomeLspLifecycleService>().isStopped(parent.parent))
            assertEquals("Startup must not relaunch the stopped parent", 2, invocations().size)
        } finally {
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
        }
    }

    fun testProjectStartupDoesNotLaunchForAnUntrustedProject() {
        val source = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        val directories = DelayedBaseDirectories(project, project.getBaseDirectories())
        project.replaceService(BaseProjectDirectories::class.java, directories, testRootDisposable)
        val editorManager = FileEditorManager.getInstance(project)
        editorManager.openFile(source, true)
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        directories.available = true
        val headlessTrust = System.getProperty("idea.trust.headless.disabled")
        try {
            System.setProperty("idea.trust.headless.disabled", "false")
            TrustedProjects.setProjectTrusted(project, false)
            assertFalse("The test must really exercise safe mode", TrustedProjects.isProjectTrusted(project))
            runProjectStartupActivities()
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            assertTrue(LspServerManager.getInstance(project)
                .getServersForProvider(BiomeLspServerSupportProvider::class.java).isEmpty())
            assertTrue("Safe mode must not invoke the executable", invocations().isEmpty())
        } finally {
            editorManager.closeFile(source)
            TrustedProjects.setProjectTrusted(project, true)
            if (headlessTrust == null) System.clearProperty("idea.trust.headless.disabled")
            else System.setProperty("idea.trust.headless.disabled", headlessTrust)
        }
    }

    fun testProjectStartupDoesNotLaunchWhenDisabled() {
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.DISABLED
        val source = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        FileEditorManager.getInstance(project).openFile(source, true)
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        runProjectStartupActivities()
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        assertTrue(LspServerManager.getInstance(project)
            .getServersForProvider(BiomeLspServerSupportProvider::class.java).isEmpty())
        assertTrue("Disabled startup must not invoke the executable", invocations().isEmpty())
    }

    @Suppress("DEPRECATION")
    fun testProjectStartupIgnoresDefaultAndDisposedProjects() {
        runProjectStartupActivities(ProjectManager.getInstance().defaultProject)
        val directory = Files.createTempDirectory("biome-startup-disposed-")
        val disposedProject = checkNotNull(ProjectManager.getInstance().createProject("biome-startup-disposed", directory.toString()))
        try {
            ApplicationManager.getApplication().runWriteAction { Disposer.dispose(disposedProject) }
            runProjectStartupActivities(disposedProject)
            assertTrue(invocations().isEmpty())
        } finally {
            if (!disposedProject.isDisposed) ApplicationManager.getApplication().runWriteAction { Disposer.dispose(disposedProject) }
            FileUtil.delete(directory.toFile())
        }
    }

    fun testManualConfigurationUsesWorkspaceSettingsWithoutCliArguments() {
        myFixture.addFileToProject("selected config.jsonc", "{}")
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        for (selected in listOf(Path.of(myFixture.tempDirPath, "selected config.jsonc").toString(), "selected config.jsonc")) {
            Files.deleteIfExists(invocationLog)
            Files.deleteIfExists(configurationLog)
            BiomeSettings.getInstance(project).configPath = selected
            start(descriptorFor(file)) {
                assertOnlyLspInvocation(Path.of(myFixture.tempDirPath))
                assertWorkspaceConfiguration(selected)
                assertEquals("Transport must preserve the selected path", selected, BiomeSettings.getInstance(project).configPath)
            }
        }
    }

    fun testAbsoluteManualConfigurationUsesSelectedTargetPath() {
        val selected = myFixture.addFileToProject("selected config.jsonc", "{}").virtualFile.path
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeSettings.getInstance(project).configPath = selected
        val descriptor = descriptorFor(file)
        useMappedTarget(descriptor)

        start(descriptor) {
            assertWorkspaceConfiguration("/mapped workspace/selected config.jsonc")
            assertEquals("file:///mapped%20workspace/index.js", descriptor.getFileUri(file))
            assertEquals(file, descriptor.findFileByUri("file:///mapped%20workspace/index.js"))
            assertEquals("Transport must not rewrite the saved override", selected,
                BiomeSettings.getInstance(project).configPath)
        }
    }

    fun testRelativeManualConfigurationKeepsTargetWorkspaceMeaning() {
        val selected = "configs/../selected config.jsonc"
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeSettings.getInstance(project).configPath = selected
        val descriptor = descriptorFor(file)
        useMappedTarget(descriptor)

        start(descriptor) {
            assertWorkspaceConfiguration(selected)
            assertEquals(selected, BiomeSettings.getInstance(project).configPath)
        }
    }

    fun testManualConfigurationDoesNotHideTargetMappingFailure() {
        val selected = myFixture.addFileToProject("selected config.jsonc", "{}").virtualFile.path
        BiomeSettings.getInstance(project).configPath = selected
        val descriptor = descriptorFor(myFixture.addFileToProject("index.js", "let value = 1;").virtualFile)
        val failure = IllegalArgumentException("Selected configuration is outside the target")
        useMappedTarget(descriptor) { throw failure }

        try {
            descriptor.getWorkspaceConfiguration(ConfigurationItem().apply { section = "biome" })
            fail("A failed conversion must not send the host path to the target")
        } catch (actual: IllegalArgumentException) {
            assertSame(failure, actual)
        }
    }

    fun testManualConfigurationPreservesTargetMappingCancellation() {
        val selected = myFixture.addFileToProject("selected config.jsonc", "{}").virtualFile.path
        BiomeSettings.getInstance(project).configPath = selected
        val descriptor = descriptorFor(myFixture.addFileToProject("index.js", "let value = 1;").virtualFile)
        val cancellation = ProcessCanceledException()
        useMappedTarget(descriptor) { throw cancellation }

        try {
            descriptor.getWorkspaceConfiguration(ConfigurationItem().apply { section = "biome" })
            fail("Cancellation must propagate out of target-path conversion")
        } catch (actual: ProcessCanceledException) {
            assertSame(cancellation, actual)
        }
    }

    fun testManualConfigurationRejectsUnmappableAbsoluteTargetPath() {
        val selected = myFixture.addFileToProject("unreachable config.jsonc", "{}").virtualFile.path
        BiomeSettings.getInstance(project).configPath = selected
        val descriptor = descriptorFor(myFixture.addFileToProject("index.js", "let value = 1;").virtualFile)
        useMappedTarget(descriptor) { null }

        val failure = org.junit.Assert.assertThrows(Exception::class.java) {
            descriptor.getWorkspaceConfiguration(ConfigurationItem().apply { section = "biome" })
        }
        assertTrue("Report the configuration that could not be mapped", failure.message.orEmpty().contains(selected))
        assertEquals(selected, BiomeSettings.getInstance(project).configPath)
    }

    fun testDirectStartupUsesSelectedSecondContentRoot() {
        val secondPath = Files.createTempDirectory("biome second content root ")
        Disposer.register(testRootDisposable) { FileUtil.delete(secondPath.toFile()) }
        Files.writeString(secondPath.resolve("biome.json"), "{}")
        Files.writeString(secondPath.resolve("index.js"), "let value = 1;")
        val secondRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondPath)!!
        secondRoot.refresh(false, true)
        ModuleRootModificationUtil.addContentRoot(myFixture.module, secondRoot.path)
        PlatformTestUtil.waitWithEventsDispatching("Secondary content root was not published", {
            project.getBaseDirectories().contains(secondRoot)
        }, 5)
        assertTrue("The fixture must retain both content roots", ModuleRootManager.getInstance(myFixture.module).contentRoots.size >= 2)
        assertTrue("The selected root must differ from project base", secondRoot.path != project.basePath)
        BiomeSettings.getInstance(project).configPath = "biome.json"
        val descriptor = descriptorFor(secondRoot.findChild("index.js")!!)
        assertEquals(listOf(secondRoot), descriptor.roots.toList())
        start(descriptor) {
            assertOnlyLspInvocation(secondPath)
            assertWorkspaceConfiguration("biome.json")
        }
    }

    fun testRelativeNodeLauncherUsesNestedBiomeRootWithoutProbing() {
        configureNodeInterpreter()
        myFixture.addFileToProject("nested root/biome.json", """{"root":true}""")
        val file = myFixture.addFileToProject("nested root/index.js", "let value = 1;").virtualFile
        // This is a controlled Node fixture; real npm/native coverage lives in the launcher tests.
        myFixture.addFileToProject("nested root/node_modules/@biomejs/biome/bin/biome", nodeLauncher())
        BiomeSettings.getInstance(project).executablePath = "./node_modules/@biomejs/biome/bin/biome"
        val descriptor = descriptorFor(file)
        assertEquals(listOf(file.parent), descriptor.roots.toList())
        start(descriptor) { assertOnlyLspInvocation(file.parent.toNioPath()) }
    }

    fun testSdkStopDuringInitializationShutsDownAfterResponseIsReleased() {
        val started = Path.of(myFixture.tempDirPath, "initializing.pid")
        val release = Path.of(myFixture.tempDirPath, "release-initialize")
        writeShellLauncher(mapOf("BIOME_STARTUP_INITIALIZE_STARTED" to started.toString(),
            "BIOME_STARTUP_INITIALIZE_RELEASE" to release.toString()))
        val descriptor = descriptorFor(myFixture.addFileToProject("index.js", "let value = 1;").virtualFile)
        val manager = LspServerManager.getInstance(project)
        var child: ProcessHandle? = null
        manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
        try {
            PlatformTestUtil.waitWithEventsDispatching("The LSP fixture did not receive initialize", {
                Files.isRegularFile(started) && Files.size(started) > 0
            }, 5)
            val initializingChild = ProcessHandle.of(Files.readString(started).toLong()).orElseThrow()
            child = initializingChild
            val server = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
            assertEquals(LspServerState.Initializing, server.state)
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
            // The SDK serializes stop with initialization. This test releases a responsive
            // peer; it does not impose prompt cleanup of a server that never responds.
            Files.writeString(release, "release")
            PlatformTestUtil.waitWithEventsDispatching("SDK stop left the released LSP process alive", { !initializingChild.isAlive }, 5)
            assertEquals(LspServerState.ShutdownNormally, server.state)
            assertOnlyLspInvocation(Path.of(myFixture.tempDirPath))
        } finally {
            Files.writeString(release, "release")
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
            child?.let { if (it.isAlive) it.destroyForcibly() }
        }
    }

    fun testSdkStopDuringProcessCreationRetainsProcessOwnership() {
        val descriptor = descriptorFor(myFixture.addFileToProject("index.js", "let value = 1;").virtualFile)
        val created = CountDownLatch(1)
        val release = CountDownLatch(1)
        val child = AtomicReference<Process>()
        val command = object : GeneralCommandLine(peerArguments + "lsp-proxy") {
            override fun createProcess(): Process {
                val process = super.createProcess()
                child.set(process)
                created.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Process creation was not released" }
                return process
            }
        }
        descriptor.javaClass.getDeclaredField("targetRun").apply { isAccessible = true }
            .set(descriptor, BiomeTargetRun.General(command))
        val manager = LspServerManager.getInstance(project)
        manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
        try {
            PlatformTestUtil.waitWithEventsDispatching("The process factory was not entered", { created.count == 0L }, 5)
            val server = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
            release.countDown()
            PlatformTestUtil.waitWithEventsDispatching("SDK stop lost the newly created LSP process", { !child.get().isAlive }, 5)
            assertEquals(LspServerState.ShutdownNormally, server.state)
        } finally {
            release.countDown()
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
            child.get()?.let { if (it.isAlive) it.destroyForcibly(); it.waitFor(1, TimeUnit.SECONDS) }
        }
    }

    private fun descriptorFor(file: VirtualFile): LspServerDescriptor {
        val descriptors = mutableListOf<LspServerDescriptor>()
        BiomeLspServerSupportProvider().fileOpened(project, file, object : LspServerSupportProvider.LspServerStarter {
            override fun ensureServerStarted(descriptor: LspServerDescriptor) { descriptors += descriptor }
        })
        return descriptors.single()
    }

    private fun runProjectStartupActivities(targetProject: Project = project) {
        // Resolve only our contribution: unrelated bundled startup activities can use older interfaces.
        val activity = ExtensionPointName.create<ProjectActivity>("com.intellij.postStartupActivity")
            .findExtension(BiomeStartupActivity::class.java) ?: return
        runWithModalProgressBlocking(project, "Test restored Biome editors") {
            activity.execute(targetProject)
        }
    }

    private class DelayedBaseDirectories(project: Project, private val directories: Set<VirtualFile>) : BaseProjectDirectories(project) {
        @Volatile var available = false
        val unavailableReads = AtomicInteger()
        override val isProcessing: Boolean get() = !available
        override fun getBaseDirectories(): Set<VirtualFile> {
            if (available) return directories
            unavailableReads.incrementAndGet()
            return emptySet()
        }
        override fun getBaseDirectoryFor(virtualFile: VirtualFile): VirtualFile? =
            getBaseDirectories().firstOrNull { VfsUtilCore.isAncestor(it, virtualFile, false) }
    }

    // Exercise the real LSP response with a controlled mapper, without launching Windows/WSL.
    private fun useMappedTarget(descriptor: LspServerDescriptor, convert: ((Path) -> String?)? = null) {
        val localRoot = Path.of(myFixture.tempDirPath)
        val targetRoot = "/mapped workspace"
        val distribution = object : WSLDistribution("Biome mapped-path fixture") {
            override fun getWslPath(path: Path): String? {
                if (convert != null) return convert(path)
                check(path.isAbsolute && path.startsWith(localRoot)) { "Unexpected host path: $path" }
                val relative = localRoot.relativize(path).toString().replace('\\', '/')
                return if (relative.isEmpty()) targetRoot else "$targetRoot/$relative"
            }

            override fun getWindowsPath(path: String): String {
                check(path == targetRoot || path.startsWith("$targetRoot/")) { "Unexpected target path: $path" }
                return localRoot.resolve(path.removePrefix(targetRoot).removePrefix("/")).toString()
            }
        }
        val command = GeneralCommandLine(executable.toString(), "lsp-proxy")
            .withWorkDirectory(localRoot.toFile()).withCharset(Charsets.UTF_8)
        descriptor.javaClass.getDeclaredField("targetRun").apply { isAccessible = true }
            .set(descriptor, BiomeTargetRun.General(command, distribution))
    }

    private fun start(descriptor: LspServerDescriptor, verify: (LspServer) -> Unit) {
        val manager = LspServerManager.getInstance(project)
        manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
        try {
            PlatformTestUtil.waitWithEventsDispatching("SDK server did not initialize; commands=${invocations()}", {
                manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
                    .find { it.descriptor === descriptor }?.state == LspServerState.Running
            }, 8)
            verify(manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single { it.descriptor === descriptor })
        } finally {
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
        }
    }

    private fun assertOnlyLspInvocation(root: Path) {
        val invocation = invocations().single().split('\t', limit = 2)
        assertEquals(root.toRealPath(), Path.of(invocation[0]).toRealPath())
        assertEquals("lsp-proxy", invocation[1])
    }

    private fun assertWorkspaceConfiguration(expected: String) {
        PlatformTestUtil.waitWithEventsDispatching("SDK did not answer workspace/configuration", {
            Files.isRegularFile(configurationLog) && Files.size(configurationLog) > 0
        }, 5)
        val response = Json.parseToJsonElement(Files.readString(configurationLog)).jsonObject
        assertEquals(expected, response.getValue("result").jsonArray.single().jsonObject.getValue("configurationPath").jsonPrimitive.content)
    }

    private fun invocations(): List<String> = if (Files.exists(invocationLog)) Files.readAllLines(invocationLog) else emptyList()

    private fun writeShellLauncher(environment: Map<String, String> = emptyMap()) {
        val exports = (environment + ("BIOME_STARTUP_CONFIGURATION_LOG" to configurationLog.toString()))
            .entries.joinToString("\n") { (key, value) -> "export $key=${quote(value)}" }
        Files.writeString(executable, "#!/bin/sh\nprintf '%s\\t%s\\n' \"\$PWD\" \"\$*\" >> ${quote(invocationLog.toString())}\n$exports\nexec ${peerArguments.joinToString(" ", transform = ::quote)} \"\$@\"\n")
        check(executable.toFile().setExecutable(true)) { "Cannot make the LSP fixture executable" }
    }

    private fun configureNodeInterpreter() {
        val node = System.getenv("PATH").split(File.pathSeparator).asSequence()
            .map { Path.of(it, "node") }.firstOrNull { Files.isExecutable(it) }
            ?: error("Node is required for the startup launcher fixture")
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(
            NodeJsInterpreterRef.create(NodeJsLocalInterpreter(node.toString())), testRootDisposable)
    }

    private fun nodeLauncher(): String = """
        |#!/usr/bin/env node
        |const result = require('child_process').spawnSync(${Json.encodeToString(executable.toString())}, process.argv.slice(2), {stdio: 'inherit'});
        |process.exit(result.status === null ? 1 : result.status);
        |
    """.trimMargin()

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
