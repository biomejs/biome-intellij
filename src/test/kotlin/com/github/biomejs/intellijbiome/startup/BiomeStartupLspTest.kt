package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.fixtures.StartupLspProcess
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
