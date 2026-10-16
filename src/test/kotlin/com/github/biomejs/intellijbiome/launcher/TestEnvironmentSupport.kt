package com.github.biomejs.intellijbiome.launcher

import com.intellij.openapi.util.SystemInfoRt
import com.intellij.util.EnvironmentUtil
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.function.Supplier

/** The test-only platform setter changed from Deferred in 253 to Supplier in 262. */
internal fun setTestEnvironment(environment: Map<String, String>) {
    val setter = EnvironmentUtil::class.java.methods.single {
        it.name == "setEnvironmentLoader" && it.parameterCount == 1
    }
    val argument = if (setter.parameterTypes.single() == Supplier::class.java) {
        Supplier { environment }
    } else {
        CompletableDeferred(environment)
    }
    setter.invoke(null, argument)
}

/** Keeps the macOS home plus daemon socket path below the Unix socket limit. */
internal fun createBiomeProcessDirectory(prefix: String): Path =
    if (SystemInfoRt.isMac) Files.createTempDirectory(Path.of("/tmp"), prefix)
    else Files.createTempDirectory(prefix)

/** Changes child-process environment only; the JVM and Gradle home stay unchanged. */
internal fun isolatedBiomeEnvironment(environment: Map<String, String>, directory: Path): Map<String, String> {
    val isolated = environment + ("XDG_CACHE_HOME" to directory.toString())
    return if (SystemInfoRt.isMac) isolated + ("HOME" to directory.toString()) else isolated
}

/** Finds the real socket for either the legacy or current daemon layout. */
internal fun findIsolatedBiomeSocket(environment: Map<String, String>, version: String): Path? {
    val cache = if (SystemInfoRt.isMac) {
        Path.of(checkNotNull(environment["HOME"]), "Library", "Caches", "dev.biomejs.biome")
    } else {
        Path.of(checkNotNull(environment["XDG_CACHE_HOME"]), "biome")
    }
    if (!Files.isDirectory(cache)) return null
    return Files.walk(cache, 2).use { paths ->
        paths.filter { it.fileName.toString() == "biome-socket-$version" && !Files.isDirectory(it) }
            .findFirst().orElse(null)
    }
}

/** Finds the native CLI installed for the test host. */
internal fun findNativeBiomeExecutable(projectDirectory: Path): Path {
    val platform = when {
        SystemInfoRt.isMac -> "darwin"
        SystemInfoRt.isLinux -> "linux"
        else -> error("Native process fixtures require macOS or Linux")
    }
    val architecture = when (val arch = System.getProperty("os.arch")) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> error("Unsupported native process fixture architecture: $arch")
    }
    val packageName = "cli-$platform-$architecture"
    return Files.walk(projectDirectory.resolve("node_modules/.pnpm")).use { paths ->
        paths.filter { it.fileName.toString() == "biome" && it.parent.fileName.toString() == packageName }
            .filter { Files.isRegularFile(it) && Files.isExecutable(it) }
            .findFirst().orElseThrow { IllegalStateException("Missing executable $packageName/biome in $projectDirectory") }
    }
}

/** Excludes Linux zombies without treating absent procfs on macOS as process exit. */
internal fun isProcessExecuting(pid: Long): Boolean {
    val process = ProcessHandle.of(pid).orElse(null) ?: return false
    if (!process.isAlive) return false
    if (!SystemInfoRt.isLinux) return process.isAlive
    val state = try {
        Files.readString(Path.of("/proc", pid.toString(), "stat"))
            .substringAfterLast(')').trimStart().firstOrNull()
    } catch (_: NoSuchFileException) {
        return process.isAlive
    }
    return state != 'Z' && state != 'X' && process.isAlive
}
