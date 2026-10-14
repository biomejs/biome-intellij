import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.io.File
import java.security.MessageDigest

val remoteRobotVersion = "0.11.21"
val testPlatformKotlinVersion = "2.2.20"

plugins {
  id("java") // Java support
  alias(libs.plugins.kotlin) // Kotlin support
  alias(libs.plugins.kotlinxSerialization) // Kotlinx Serialization
  alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
  jvmToolchain(21)
}

// Configure project's dependencies
repositories {
  mavenCentral()

  // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
  intellijPlatform {
    defaultRepositories()
  }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
  implementation(libs.kotlinxSerialization)
  testImplementation(enforcedPlatform("org.jetbrains.kotlin:kotlin-bom:$testPlatformKotlinVersion"))

  testImplementation(libs.junit)
  // TODO:
  //  Warning:(37, 22)  Provides transitive vulnerable dependency maven:org.assertj:assertj-core:3.17.2 CVE-2026-24400 7.3 AssertJ has XML External Entity (XXE) vulnerability when parsing untrusted XML via isXmlEqualTo assertion  Results powered by Mend.io
  testImplementation("com.intellij.remoterobot:remote-robot:$remoteRobotVersion")
  testImplementation("com.intellij.remoterobot:remote-fixtures:$remoteRobotVersion")
  // Remote Robot still brings an older Gson that breaks package.json parsing and the LSP JSON adapter at runtime.
  testImplementation("com.google.code.gson:gson:2.11.0")
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.0")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.9.3")

  // Logging Network Calls
  testImplementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

  // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
  intellijPlatform {
    create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion")) {
      // TODO: consider using the installer release and drop the below line
      useInstaller = false
    }

    // IDE tooling needs the matching JetBrains Runtime when the SDK has no bundled runtime.
    jetbrainsRuntime()

    // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
    bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

    // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
    plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

    testFramework(TestFrameworkType.Platform)
  }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
  pluginConfiguration {
    version = providers.gradleProperty("pluginVersion")

    ideaVersion {
      sinceBuild = providers.gradleProperty("pluginSinceBuild")
    }
  }

  signing {
    certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    privateKey = providers.environmentVariable("PRIVATE_KEY")
    password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
  }

  publishing {
    token = providers.environmentVariable("PUBLISH_TOKEN")
    // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
    // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
  }

  pluginVerification {
    ides {
      recommended()
    }
  }
}

tasks {
  wrapper {
    gradleVersion = providers.gradleProperty("gradleVersion").get()
  }
}

intellijPlatformTesting {
  runIde {
    register("runIdeForUiTests") {
      task {
        jvmArgumentProviders += CommandLineArgumentProvider {
          listOf(
            "-Drobot-server.port=8082",
            "-Dide.mac.message.dialogs.as.sheets=false",
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Djb.consents.confirmation.enabled=false",
            "-Dide.mac.file.chooser.native=false",
            "-DjbScreenMenuBar.enabled=false",
            "-Dapple.laf.useScreenMenuBar=false",
            "-Didea.trust.all.projects=true",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Deap.require.license=false"
          )
        }
      }

      plugins {
        robotServerPlugin()
      }
    }
  }
}

// Keep the result guard independent of the build script for configuration-cache reuse.
class RequiredTests : TestListener {
  override fun beforeSuite(suite: TestDescriptor) {}
  override fun beforeTest(test: TestDescriptor) {}
  override fun afterTest(test: TestDescriptor, result: TestResult) {}
  override fun afterSuite(suite: TestDescriptor, result: TestResult) {
    if (suite.parent == null) {
      check(result.testCount > 0) { "No tests ran" }
      check(result.skippedTestCount == 0L) { "${result.skippedTestCount} tests were skipped" }
    }
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform {
    if (providers.gradleProperty("uiTests").isPresent) includeTags("remote-robot")
    else excludeTags("remote-robot")
  }
  // Execute tests on every invocation while preserving compilation and dependency caches.
  outputs.upToDateWhen { false }
  outputs.doNotCacheIf("Test results must come from this invocation") { true }
  failOnNoDiscoveredTests = true
  onlyIf("Test classes must exist") {
    check((it as Test).testClassesDirs.asFileTree.matching { include("**/*.class") }.files.isNotEmpty()) {
      "No test classes were compiled"
    }
    true
  }
  addTestListener(RequiredTests())
  // Biome daemons share their cache. Isolate each checkout and runtime task,
  // keeping the path short enough for Unix domain sockets.
  val cacheKey = MessageDigest.getInstance("SHA-256")
    .digest("${projectDir.absolutePath}:$name".toByteArray(Charsets.UTF_8))
    .take(8).joinToString("") { "%02x".format(it) }
  environment("XDG_CACHE_HOME", File(System.getProperty("java.io.tmpdir"), "biome-test-$cacheKey").absolutePath)
}
