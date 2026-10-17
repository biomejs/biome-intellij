package com.github.biomejs.intellijbiome.launcher

import com.intellij.openapi.util.SystemInfoRt
import com.intellij.util.EnvironmentUtil
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Files
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
