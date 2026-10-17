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
            "\\\\server\\share\\biome",
            "C:\\tools\\biome.js",
            "/home/user/biome",
            "./node_modules/.bin/biome",
            ".\\node_modules\\.bin\\biome",
            "..\\biome",
            "biome",
            "//wsl.localhost/Ubuntu",
        )) {
            assertFalse("Target selection must preserve the executable's distribution: $path",
                supportsManualNodeTarget(path, interpreter))
        }
    }

    fun testLocalInterpreterCannotClaimWslLauncher() {
        val interpreter = NodeJsLocalInterpreter("C:/tools/node.exe")
        assertFalse(supportsManualNodeTarget("//wsl.localhost/Ubuntu/home/user/biome", interpreter))
    }

    fun testLocalInterpreterWithUncPathCannotClaimLocalLauncher() {
        val interpreter = NodeJsLocalInterpreter("//wsl.localhost/Ubuntu/usr/bin/node")
        assertFalse(supportsManualNodeTarget("C:/tools/biome.js", interpreter))
    }
}
