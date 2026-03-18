package com.intellij.lang.javascript.modules

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.CodeInsightTestFixture

private const val PACKAGE_LOCKS_STORE_FOLDER_NAME = "_package-locks-store"
private const val PACKAGE_JSON_NAME = "package.json"
private const val PNPM_LOCKFILE_NAME = "pnpm-lock.yaml"

class TestNpmPackageInstaller(
    private val fixture: CodeInsightTestFixture,
    private val copyPackageJson: Boolean = true,
) {
    fun installForTest(testClass: Class<*>, projectDir: VirtualFile, installSubdir: String? = null) {
        val installDir = if (installSubdir != null) {
            projectDir.findChild(installSubdir) ?: error("Subdirectory not found: $installSubdir")
        } else {
            projectDir
        }

        val requestedPackageSpec = testClass.getAnnotation(TestNpmPackage::class.java)?.packageSpec
        if (requestedPackageSpec != null) {
            installRequestedTestNpmPackage(projectDir, installDir, requestedPackageSpec)
        } else if (installDir.findChild(PACKAGE_JSON_NAME) != null) {
            installNodeModules(installDir)
        }
    }

    private fun installRequestedTestNpmPackage(
        projectDir: VirtualFile,
        installDir: VirtualFile,
        requestedPackageSpec: String,
    ) {
        val cacheDirName = convertNpmPackageSpecToFileSystemName(requestedPackageSpec)
        val cacheRelativeDir = "$PACKAGE_LOCKS_STORE_FOLDER_NAME/$cacheDirName"
        val targetRelativeDir = relativePathFromProjectRoot(projectDir, installDir)

        if (copyPackageJson) {
            fixture.copyFileToProject(
                "$cacheRelativeDir/$PACKAGE_JSON_NAME",
                relativePath(targetRelativeDir, PACKAGE_JSON_NAME)
            )
        }
        fixture.copyFileToProject(
            "$cacheRelativeDir/$PNPM_LOCKFILE_NAME",
            relativePath(targetRelativeDir, PNPM_LOCKFILE_NAME)
        )

        installNodeModules(installDir)
    }

    private fun installNodeModules(installDir: VirtualFile) {
        val commandLine = GeneralCommandLine(
            if (isWindows()) {
                listOf("cmd", "/c", "corepack pnpm install --frozen-lockfile")
            } else {
                listOf("sh", "-c", "corepack pnpm install --frozen-lockfile")
            }
        ).withWorkDirectory(installDir.path)
            .withCharset(Charsets.UTF_8)

        val output = CapturingProcessHandler(commandLine).runProcess(300_000)
        check(output.exitCode == 0) {
            buildString {
                appendLine("Failed to install npm dependencies for test fixture in ${installDir.path}")
                appendLine("Exit code: ${output.exitCode}")
                if (output.stdout.isNotBlank()) {
                    appendLine("stdout:")
                    appendLine(output.stdout)
                }
                if (output.stderr.isNotBlank()) {
                    appendLine("stderr:")
                    appendLine(output.stderr)
                }
            }
        }

        VfsUtil.markDirtyAndRefresh(false, true, true, installDir)
    }

    private fun convertNpmPackageSpecToFileSystemName(packageSpec: String): String =
        packageSpec.replace(Regex("[@./]"), "_")

    private fun relativePathFromProjectRoot(projectDir: VirtualFile, dir: VirtualFile): String {
        if (dir == projectDir) {
            return "."
        }

        return dir.path.removePrefix(projectDir.path).removePrefix("/")
    }

    private fun relativePath(dir: String, fileName: String): String =
        if (dir == ".") fileName else "$dir/$fileName"

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
