package com.github.biomejs.intellijbiome.pages

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.fixtures.DefaultXpath
import com.intellij.remoterobot.fixtures.FixtureName
import com.intellij.remoterobot.search.locators.byXpath
import java.time.Duration

fun RemoteRobot.statusBar(function: StatusbarFrame.() -> Unit) {
    find<StatusbarFrame>(timeout = Duration.ofSeconds(10)).apply(function)
}

@FixtureName("Statusbar frame")
@DefaultXpath("IdeStatusBarImpl type", "//div[@class='IdeStatusBarImpl']")
class StatusbarFrame(remoteRobot: RemoteRobot, remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {

    val statusBarPanel
        get() = find<ContainerFixture>(
            byXpath(
                "StatusBarPanel",
                "//div[@class='StatusBarPanel'][.//div[@class='CodeStyleStatusBarPanel']]"
            )
        )

    fun byContainsText(text: String) =
        byXpath("text $text", "//div[contains(@text,'$text') and @class='WithIconAndArrows']")

    val text: String
        get() = callJs("component.getText();")
}
