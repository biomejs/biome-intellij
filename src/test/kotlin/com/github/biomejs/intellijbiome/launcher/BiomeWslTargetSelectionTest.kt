package com.github.biomejs.intellijbiome.launcher

import com.github.biomejs.intellijbiome.supportsManualNodeTarget
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import junit.framework.TestCase

class BiomeWslTargetSelectionTest : TestCase() {
    fun testMatchingWslDistributionAllowsNodeLauncherInspection() {
        val interpreter = WslNodeInterpreter("Ubuntu", "/usr/bin/node")
        for (path in listOf(
            "\\\\wsl$\\Ubuntu\\home\\user\\biome",
            "\\\\wsl.localhost\\Ubuntu\\home\\user\\biome",
            "//wsl$/Ubuntu/home/user/biome",
            "//WSL.LOCALHOST/ubuntu/home/user/biome",
            "/\\wsl.localhost/Ubuntu/home/user/biome",
            "\\/wsl$/Ubuntu/home/user/biome",
            "//wsl.localhost\\Ubuntu/home\\user/biome",
            // A nonempty lexical suffix remains inspectable; filesystem checks happen later.
            "//wsl.localhost/Ubuntu//",
            "\\\\wsl$\\Ubuntu\\\\",
        )) {
            assertTrue("Matching WSL paths may use the configured Node interpreter: $path",
                supportsManualNodeTarget(path, interpreter))
        }
    }

    fun testForeignAndLocalPathsCannotUseWslInterpreter() {
        val interpreter = WslNodeInterpreter("Ubuntu", "/usr/bin/node")
        for (path in listOf(
            "\\\\wsl$\\Debian\\home\\user\\biome",
            "//wsl.localhost/Ubuntu-Other/home/user/biome",
            "/\\wsl.localhost/Debian/home/user/biome",
            "\\\\server\\share\\biome",
            "C:\\tools\\biome.js",
            "/home/user/biome",
            "./node_modules/.bin/biome",
            ".\\node_modules\\.bin\\biome",
            "..\\biome",
            "biome",
            "",
            "/",
            "\\",
            "//",
            "\\\\",
            "//wsl.localhost",
            "//wsl.localhost/",
            "//wsl.localhost//biome",
            "//wsl.localhost/Ubuntu",
            "//wsl.localhost/Ubuntu/",
            "\\\\wsl$\\Ubuntu\\",
            "/\\wsl.localhost/Ubuntu/",
        )) {
            assertFalse("Target selection must preserve the executable's distribution: $path",
                supportsManualNodeTarget(path, interpreter))
        }
    }

    fun testLocalInterpreterCannotClaimWslLauncher() {
        val interpreter = NodeJsLocalInterpreter("C:/tools/node.exe")
        for (path in listOf(
            "//wsl.localhost/Ubuntu/home/user/biome",
            "/\\wsl.localhost/Ubuntu/home/user/biome",
            "\\/wsl.localhost/Ubuntu/home/user/biome",
            "//server/share/biome",
            "//",
            "\\\\",
            "/\\",
            "\\/",
        )) {
            assertFalse("A lexical UNC prefix must not select a local interpreter: $path",
                supportsManualNodeTarget(path, interpreter))
        }
    }

    fun testLocalInterpreterWithUncPathCannotClaimLocalLauncher() {
        for (path in listOf(
            "//wsl.localhost/Ubuntu/usr/bin/node",
            "/\\wsl.localhost/Ubuntu/usr/bin/node",
            "\\/wsl.localhost/Ubuntu/usr/bin/node",
            "//server/share/node.exe",
            "//",
        )) {
            val interpreter = NodeJsLocalInterpreter(path)
            assertFalse("An interpreter's UNC prefix must not be treated as local: $path",
                supportsManualNodeTarget("C:/tools/biome.js", interpreter))
        }
    }
}
