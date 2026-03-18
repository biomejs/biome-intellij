package com.intellij.lang.javascript.modules

/**
 * Declares a pinned npm package setup for a platform test using [TestNpmPackageInstaller].
 *
 * Tests must provide cached `package.json` and `pnpm-lock.yaml` files under:
 * `_package-locks-store/<packageSpec with @ and . replaced by _>/`
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TestNpmPackage(val packageSpec: String)
