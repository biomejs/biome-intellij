package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.pages.*
import com.github.biomejs.intellijbiome.utils.RemoteRobotExtension
import com.github.biomejs.intellijbiome.utils.StepsLogger
import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.waitFor
import com.intellij.remoterobot.utils.waitForIgnoringError
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.Point
import java.io.File
import java.time.Duration.ofMinutes


@ExtendWith(RemoteRobotExtension::class)
class BasicProjectNpmTest {


    init {
        StepsLogger.init()
    }

    @BeforeEach
    fun waitForIde(remoteRobot: RemoteRobot) {
        waitForIgnoringError(ofMinutes(3)) { remoteRobot.callJs("true") }
    }

    @Test
    fun checkStatusBarVersion(remoteRobot: RemoteRobot) = with(remoteRobot) {
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

        @JvmStatic
        @BeforeAll
        fun selectProject(remoteRobot: RemoteRobot) = with(remoteRobot) {
            welcomeFrame {
                openProjectLink.click()
                dialog("Open File or Project") {
                    directoryPath.text = basicProjectPath.absolutePath
                    button("OK").click()
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun closeProject(remoteRobot: RemoteRobot) = with(remoteRobot) {
            idea {
                menuBar.select("File", "Close Project")
            }
        }
    }
}
