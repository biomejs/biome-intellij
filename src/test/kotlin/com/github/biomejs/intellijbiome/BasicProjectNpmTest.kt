package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.pages.*
import com.github.biomejs.intellijbiome.utils.RemoteRobotExtension
import java.awt.event.KeyEvent.*
import com.github.biomejs.intellijbiome.utils.StepsLogger
import com.github.biomejs.intellijbiome.utils.isAvailable
import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.launcher.Ide
import com.intellij.remoterobot.launcher.IdeDownloader
import com.intellij.remoterobot.launcher.IdeLauncher
import com.intellij.remoterobot.launcher.Os
import com.intellij.remoterobot.stepsProcessing.StepLogger
import com.intellij.remoterobot.stepsProcessing.StepWorker
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import com.intellij.remoterobot.utils.waitForIgnoringError
import okhttp3.OkHttpClient
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import java.awt.Point
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Duration.ofMinutes
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO


@ExtendWith(RemoteRobotExtension::class)
class BasicProjectNpmTest {
    
    init {
        StepsLogger.init()
    }

    @BeforeEach
    fun waitForIde() {
        waitForIgnoringError(ofMinutes(3)) { remoteRobot.callJs("true") }
    }

    @Test
    fun checkStatusBarVersion() = with(remoteRobot) {
        idea {
            step("Open index.js file") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }

                step("Open file") {
                    openFile("index.js")
                    val editor = editor("index.js")
                    editor.click(Point(0, 0))
                }
            }

            step("Check Biome's version in statusbar") {
                statusBar {
                    val biomeWidget = byContainsText("Biome")
                    val version = biomeWidget.callJs<String>("component.getText();")

                    assert(version == "Biome 1.4.1")
                }
            }
        }
    }

    companion object {
        private val basicProjectPath = File("src/test/testData/basic-project")
        private var ideaProcess: Process? = null
        private var tmpDir: Path = Files.createTempDirectory("launcher")
        private lateinit var remoteRobot: RemoteRobot

        @JvmStatic
        @BeforeAll
        fun startup() {
            StepWorker.registerProcessor(StepLogger())

            val client = OkHttpClient()
            remoteRobot = RemoteRobot("http://localhost:8082", client)
            val ideDownloader = IdeDownloader(client)

            val pathToIde = ideDownloader.downloadAndExtract(Ide.IDEA_ULTIMATE, tmpDir, Ide.BuildType.RELEASE)

            println("fixing vmoptions files...")
            val ideBinDir = pathToIde.resolve(
                when (Os.hostOS()) {
                    Os.MAC -> "Contents/bin"
                    else -> "bin"
                }
            )
            Files
                .list(ideBinDir)
                .filter {
                    val filename = it.fileName.toString()
                    filename == "jetbrains_client.vmoptions"
                }
                .forEach {
                    println("Deleting problematic file $it")
                    Files.delete(it)
                }

            ideaProcess = IdeLauncher.launchIde(
                pathToIde,
                mapOf(
                    "robot-server.port" to 8082,
                    "ide.mac.message.dialogs.as.sheets" to false,
                    "jb.privacy.policy.text" to "<!--999.999-->",
                    "jb.consents.confirmation.enabled" to false,
                    "ide.mac.file.chooser.native" to false,
                    "jbScreenMenuBar.enabled" to false,
                    "apple.laf.useScreenMenuBar" to false,
                    "idea.trust.all.projects" to true,
                    "ide.show.tips.on.startup.default.value" to false,
                    "eap.require.license" to false
                ),
                emptyList(),
                listOf(ideDownloader.downloadRobotPlugin(tmpDir)),
                tmpDir
            )
            waitFor(Duration.ofSeconds(120), Duration.ofSeconds(5)) {
                remoteRobot.isAvailable()
            }

            with(remoteRobot) {
                welcomeFrame {
                    openProjectLink.click()
                    dialog("Open File or Project") {
                        directoryPath.text = basicProjectPath.absolutePath
                        button("OK").click()
                    }
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun cleanup() = with(remoteRobot) {
            ideaProcess?.destroy()
            tmpDir.toFile().deleteRecursively()
            idea {
                menuBar.select("File", "Close Project")
            }
        }
    }
}
