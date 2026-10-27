package com.github.biomejs.intellijbiome.launcher

import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.BiomeTargetRunBuilder
import com.github.biomejs.intellijbiome.GeneralProcessCommandBuilder
import com.github.biomejs.intellijbiome.ProcessCommandParameter
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutput
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.util.EnvironmentUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BiomeLauncherTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    @TestNpmPackage("@biomejs/biome@2.5.15") private class Version2

    override fun setUp() {
        super.setUp()
        assertTrue("Required launcher process tests run on macOS and Linux", SystemInfoRt.isMac || SystemInfoRt.isLinux)
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.MANUAL
        val configured = Path.of(myFixture.tempDirPath, "configured node with spaces")
        Files.createSymbolicLink(configured, executableOnPath("node"))
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(
            NodeJsInterpreterRef.create(NodeJsLocalInterpreter(configured.toString())), testRootDisposable
        )
    }

    fun testVersion2NpmLauncherUsesConfiguredInterpreterWithoutPathNode() = checkPackage(Version2::class.java, "2.5.15")

    private fun checkPackage(packageClass: Class<*>, version: String) {
        val root = install(packageClass, "package with spaces")
        val launcher = root.resolve("node_modules/@biomejs/biome/bin/biome")
        val native = findNativeBiomeExecutable(root)
        withoutPathNode {
            val scriptRun = versionRun(launcher)
            assertEquals("Version: $version", output(scriptRun))
            assertTrue("A Node shebang launcher must use the configured interpreter", scriptRun is BiomeTargetRun.Node)
            val nativeRun = versionRun(native)
            assertTrue("The native executable must run directly", nativeRun is BiomeTargetRun.General)
            assertEquals("Version: $version", output(nativeRun))
        }
    }

    fun testNodeScriptPreservesWorkingDirectoryArgumentsAndEnvironment() {
        val root = Path.of(myFixture.tempDirPath)
        val script = root.resolve("selected launcher with spaces.js")
        val nested = Files.createDirectories(root.resolve("nested cwd with spaces"))
        Files.writeString(script, "#!/usr/bin/env node\nconsole.log(JSON.stringify([process.cwd(), process.argv.slice(2), process.env.PATH, process.env.BIOME_BINARY]));\n")
        assertTrue(script.toFile().setExecutable(true))
        withoutPathNode(mapOf("BIOME_BINARY" to "chosen native with spaces")) { path ->
            val run = BiomeTargetRunBuilder(project).getBuilder(script.toString(), nested.toString())
                .addParameters(listOf(ProcessCommandParameter.Value("argument with spaces")))
                .build()
            // The SDK prepends the interpreter's directory; its filename is intentionally not `node`.
            val targetPath = "$root${java.io.File.pathSeparator}$path"
            val expectedWorkingDirectory = nested.toRealPath()
            assertFalse(Files.isExecutable(root.resolve("node")))
            assertEquals("[\"$expectedWorkingDirectory\",[\"argument with spaces\"],\"$targetPath\",\"chosen native with spaces\"]", output(run))
        }
    }

    fun testShellWrapperKeepsItsEnvironmentSetup() {
        val root = install(Version2::class.java, "selected package")
        val wrapper = root.resolve("shell launcher.js")
        val native = findNativeBiomeExecutable(root)
        Files.writeString(wrapper, "#!/bin/sh\nexport BIOME_WRAPPER_MARKER=kept\nprintf '%s\\n' \"\$BIOME_WRAPPER_MARKER\"\nexec ${quote(native.toString())} \"\$@\"\n")
        assertTrue(wrapper.toFile().setExecutable(true))
        withoutPathNode {
            val run = versionRun(wrapper)
            assertTrue("A .js suffix must not bypass a shell wrapper", run is BiomeTargetRun.General)
            assertEquals("kept\nVersion: 2.5.15", output(run))
        }
    }

    fun testNpmLauncherPreservesBiomeBinaryOverride() {
        val root = install(Version2::class.java, "selected package")
        val override = root.resolve("override executable with spaces")
        Files.writeString(override, "#!/bin/sh\nprintf 'override marker: %s\\n' \"\$1\"\n")
        assertTrue(override.toFile().setExecutable(true))
        withoutPathNode(mapOf("BIOME_BINARY" to override.toString())) {
            assertEquals("override marker: --version", output(versionRun(root.resolve("node_modules/@biomejs/biome/bin/biome"))))
        }
    }

    fun testNativeDoesNotRequireConfiguredNodeInterpreter() {
        val root = install(Version2::class.java, "native package")
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(NodeJsInterpreterRef.create(
            NodeJsLocalInterpreter(Path.of(myFixture.tempDirPath, "missing node").toString())
        ))
        withoutPathNode { assertEquals("Version: 2.5.15", output(versionRun(findNativeBiomeExecutable(root)))) }
    }

    fun testNativeWithJavaScriptSuffixStillRunsDirectly() {
        val root = install(Version2::class.java, "native package")
        val selected = root.resolve("native with javascript suffix.js")
        Files.copy(findNativeBiomeExecutable(root), selected)
        assertTrue(selected.toFile().setExecutable(true))
        withoutPathNode {
            val run = versionRun(selected)
            assertTrue("Source content must decide dispatch, not the filename", run is BiomeTargetRun.General)
            assertEquals("Version: 2.5.15", output(run))
        }
    }

    fun testEnvironmentSettingShebangIsNotStripped() {
        val script = Path.of(myFixture.tempDirPath, "env wrapper.js")
        Files.writeString(script, "#!/usr/bin/env -S BIOME_BINARY=selected node\nconsole.log('wrapper');\n")
        val run = versionRun(script)
        assertTrue("Environment/option setup in a shebang must remain with its wrapper", run is BiomeTargetRun.General)
    }

    fun testRecognizedShebangsUseConfiguredInterpreter() {
        val script = Path.of(myFixture.tempDirPath, "node launcher")
        for (shebang in listOf("#!/usr/bin/env node", "#!/missing/node", "#! /usr/bin/env\tnode\r")) {
            Files.writeString(script, "$shebang\nconsole.log('configured');\n")
            assertTrue(script.toFile().setExecutable(true))
            withoutPathNode {
                val run = versionRun(script)
                assertTrue("Recognized shebang must use the configured interpreter: $shebang", run is BiomeTargetRun.Node)
                assertEquals("configured", output(run))
            }
        }
    }

    fun testNodeShebangSymlinkUsesConfiguredInterpreter() {
        val root = Path.of(myFixture.tempDirPath)
        val script = root.resolve("node launcher")
        Files.writeString(script, "#!/usr/bin/env node\nconsole.log('symlink');\n")
        assertTrue(script.toFile().setExecutable(true))
        val link = Files.createSymbolicLink(root.resolve("selected symlink"), script)
        withoutPathNode {
            val run = versionRun(link)
            assertTrue("The selected npm-style symlink must use the configured interpreter", run is BiomeTargetRun.Node)
            assertEquals("symlink", output(run))
        }
    }

    fun testUnrecognizedShebangsKeepDirectDispatch() {
        val script = Path.of(myFixture.tempDirPath, "other launcher.js")
        for (shebang in listOf("#!/usr/bin/env node --no-warnings", "#!/usr/bin/node --no-warnings",
            "#!/usr/bin/env " + " ".repeat(257) + "node", "#!/usr/bin/env nodejs", "#!/bin/sh")) {
            Files.writeString(script, "$shebang\n")
            assertTrue("Unrecognized shebang must retain its own setup: $shebang",
                BiomeTargetRunBuilder(project).getBuilder(script.toString()) is GeneralProcessCommandBuilder)
        }
    }

    fun testManualLauncherKeepsSelectedTarget() {
        val script = Path.of(myFixture.tempDirPath, "local node launcher")
        Files.writeString(script, "#!/usr/bin/env node\nconsole.log('local');\n")
        val manager = NodeJsInterpreterManager.getInstance(project)
        val local = manager.interpreter as NodeJsLocalInterpreter
        val remote = WslNodeInterpreter("PR47-unavailable-distribution", "/usr/bin/node")
        manager.setInterpreterRef(NodeJsInterpreterRef.create(remote))
        assertTrue("A selected local script must not be moved to a WSL interpreter target",
            BiomeTargetRunBuilder(project).getBuilder(script.toString()) is GeneralProcessCommandBuilder)

        // Decision coverage only: these path/target pairs do not claim WSL process execution.
        for (unc in listOf("\\\\wsl$\\Ubuntu\\home\\user\\biome", "\\\\wsl.localhost\\Ubuntu\\home\\user\\biome",
            "//WSL$/Ubuntu/home/user/biome", "//wsl.localhost/Other/home/user/biome", "\\\\server\\share\\biome")) {
            manager.setInterpreterRef(NodeJsInterpreterRef.create(local))
            assertTrue("UNC selection must retain direct target dispatch: $unc",
                BiomeTargetRunBuilder(project).getBuilder(unc) is GeneralProcessCommandBuilder)
            manager.setInterpreterRef(NodeJsInterpreterRef.create(remote))
            assertTrue("Matching or foreign WSL interpreter must retain direct dispatch: $unc",
                BiomeTargetRunBuilder(project).getBuilder(unc) is GeneralProcessCommandBuilder)
            manager.setInterpreterRef(NodeJsInterpreterRef.create(NodeJsLocalInterpreter(unc)))
            assertTrue("A local-interpreter object with a UNC executable is not a verified local target",
                BiomeTargetRunBuilder(project).getBuilder(script.toString()) is GeneralProcessCommandBuilder)
        }
    }

    fun testRelativeNodeLauncherUsesProjectWorkingDirectory() {
        val root = projectWorkingDirectory()
        val script = Files.createTempFile(root, "relative node ", ".js")
        try {
            Files.writeString(script, "#!/usr/bin/env node\nconsole.log('project node');\n")
            assertTrue(script.toFile().setExecutable(true))
            withoutPathNode {
                val run = relativeRun("./${script.fileName}")
                assertEquals("project node", output(run))
                assertTrue("Relative Node launcher must use the configured interpreter", run is BiomeTargetRun.Node)
            }
        } finally {
            Files.deleteIfExists(script)
        }
    }

    fun testRelativeNodeLauncherUsesSelectedWorkingDirectory() {
        val root = projectWorkingDirectory()
        val nested = Files.createTempDirectory(root, "selected launcher root ")
        val script = Files.createTempFile(nested, "relative launcher ", ".js")
        val decoy = root.resolve(script.fileName)
        try {
            Files.writeString(script, "#!/usr/bin/env node\nconsole.log(process.cwd()); console.log(process.argv[2]);\n")
            Files.writeString(decoy, "#!/bin/sh\nprintf 'wrong project launcher\\n'\n")
            assertTrue(script.toFile().setExecutable(true))
            assertTrue(decoy.toFile().setExecutable(true))
            withoutPathNode {
                val run = relativeRun("./${script.fileName}", nested)
                assertEquals("${nested.toRealPath()}\nargument with spaces", output(run))
                assertTrue("Selected-directory Node script must use the configured interpreter", run is BiomeTargetRun.Node)
            }
        } finally {
            Files.deleteIfExists(decoy)
            Files.deleteIfExists(script)
            Files.deleteIfExists(nested)
        }
    }

    fun testRelativeShellWrapperUsesSelectedWorkingDirectory() {
        val root = projectWorkingDirectory()
        val nested = Files.createTempDirectory(root, "selected shell root ")
        val wrapper = Files.createTempFile(nested, "relative launcher ", ".js")
        val decoy = root.resolve(wrapper.fileName)
        try {
            Files.writeString(wrapper, "#!/bin/sh\nexport BIOME_WRAPPER_MARKER=kept\nprintf '%s\\n' \"\$BIOME_WRAPPER_MARKER\"\n")
            Files.writeString(decoy, "#!/usr/bin/env node\nconsole.log('wrong project launcher');\n")
            assertTrue(wrapper.toFile().setExecutable(true))
            assertTrue(decoy.toFile().setExecutable(true))
            withoutPathNode {
                val run = relativeRun("./${wrapper.fileName}", nested)
                assertTrue("Selected shell wrapper must retain its setup despite the project-root Node decoy",
                    run is BiomeTargetRun.General)
                assertEquals("kept", output(run))
            }
        } finally {
            Files.deleteIfExists(decoy)
            Files.deleteIfExists(wrapper)
            Files.deleteIfExists(nested)
        }
    }

    fun testBareLauncherNameKeepsPathLookup() {
        val root = projectWorkingDirectory()
        val decoy = Files.createFile(root.resolve("sh"))
        try {
            Files.writeString(decoy, "#!/usr/bin/env node\nconsole.log('wrong project launcher');\n")
            assertTrue(decoy.toFile().setExecutable(true))
            // Linux resolves bare commands using the JVM's original PATH, not EnvironmentUtil overrides.
            val run = BiomeTargetRunBuilder(project).getBuilder("sh")
                .addParameters(listOf(
                    ProcessCommandParameter.Value("-c"),
                    ProcessCommandParameter.Value("printf 'path launcher\\n'"),
                )).build()
            assertTrue("A bare executable name must retain PATH lookup", run is BiomeTargetRun.General)
            assertEquals("path launcher", output(run))
        } finally {
            Files.deleteIfExists(decoy)
        }
    }

    fun testRelativeNodeLauncherKeepsUncWorkingDirectoryBoundary() {
        val root = Path.of(myFixture.tempDirPath)
        val script = root.resolve("relative node launcher")
        Files.writeString(script, "#!/usr/bin/env node\nconsole.log('local');\n")
        assertTrue(script.toFile().setExecutable(true))
        // POSIX Path normalizes this lexical UNC prefix; this checks dispatch only, not WSL execution.
        val uncWorkingDirectory = "/$root"
        assertTrue("An unverified UNC working directory must retain direct dispatch",
            BiomeTargetRunBuilder(project).getBuilder("./${script.fileName}", uncWorkingDirectory)
                is GeneralProcessCommandBuilder)
    }

    private fun projectWorkingDirectory(): Path {
        val root = Path.of(checkNotNull(project.basePath))
        val createdDirectories = generateSequence(root) { it.parent }.takeWhile { !Files.exists(it) }.toList()
        Files.createDirectories(root)
        com.intellij.openapi.util.Disposer.register(testRootDisposable) {
            createdDirectories.forEach { Files.deleteIfExists(it) }
        }
        return root
    }

    private fun relativeRun(executable: String, workingDirectory: Path? = null): BiomeTargetRun {
        val target = BiomeTargetRunBuilder(project)
        val builder = if (workingDirectory == null) target.getBuilder(executable)
            else target.getBuilder(executable, workingDirectory.toString())
        return builder.addParameters(listOf(ProcessCommandParameter.Value("argument with spaces"))).build()
    }

    private fun install(packageClass: Class<*>, subdir: String): Path {
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        myFixture.tempDirFixture.findOrCreateDir(subdir)
        val root = myFixture.tempDirFixture.getFile(".")!!
        TestNpmPackageInstaller(myFixture).installForTest(packageClass, root, subdir)
        return Path.of(root.path, subdir)
    }

    private fun versionRun(executable: Path): BiomeTargetRun = BiomeTargetRunBuilder(project)
        .getBuilder(executable.toString())
        .addParameters(listOf(ProcessCommandParameter.Value("--version"))).build()

    private fun output(run: BiomeTargetRun): String {
        val handler = run.startProcess()
        try {
            val completion = CompletableFuture<ProcessOutput>()
            handler.addProcessListener(object : CapturingProcessAdapter() {
                override fun processTerminated(event: ProcessEvent) {
                    super.processTerminated(event)
                    completion.complete(output)
                }
            })
            handler.startNotify()
            val result = completion.get(10, TimeUnit.SECONDS)
            assertEquals(result.stderr, 0, result.exitCode)
            return result.stdout.trimEnd('\r', '\n')
        } finally {
            if (handler.process.isAlive) handler.destroyProcess()
            if (!handler.waitFor(2_000)) {
                handler.process.destroyForcibly()
                check(handler.waitFor(2_000)) { "Launcher child did not terminate" }
            }
        }
    }

    private fun withoutPathNode(extra: Map<String, String> = emptyMap(), action: (Path) -> Unit) {
        val original = EnvironmentUtil.getEnvironmentMap().toMap()
        val path = Files.createDirectories(Path.of(myFixture.tempDirPath, "path without node"))
        if (SystemInfoRt.isLinux && !Files.exists(path.resolve("ldd"))) {
            Files.createSymbolicLink(path.resolve("ldd"), executableOnPath("ldd"))
        }
        assertFalse(Files.exists(path.resolve("node")))
        setTestEnvironment(original + extra + ("PATH" to path.toString()))
        try { action(path) } finally { setTestEnvironment(original) }
    }

    private fun executableOnPath(name: String): Path = EnvironmentUtil.getEnvironmentMap().getValue("PATH")
        .split(java.io.File.pathSeparator).map { Path.of(it, name) }.firstOrNull(Files::isExecutable)
        ?: error("Required $name executable not found")

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
