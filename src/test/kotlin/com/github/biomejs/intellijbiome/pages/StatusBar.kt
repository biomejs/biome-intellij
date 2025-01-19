package com.github.biomejs.intellijbiome.pages

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.*
import com.intellij.remoterobot.search.locators.byXpath
import java.time.Duration

fun RemoteRobot.statusBar(function: StatusbarFrame.() -> Unit) {
    find<StatusbarFrame>(timeout = Duration.ofSeconds(10)).apply(function)
}

@FixtureName("Statusbar frame")
@DefaultXpath("IdeStatusBarImpl type", "//div[@class='IdeStatusBarImpl']")
class StatusbarFrame(remoteRobot: RemoteRobot,
    remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {

    val statusBarPanel
        get() = find<ContainerFixture>(
            byXpath(
                "StatusBarPanel",
                "//div[@class='StatusBarPanel'][.//div[@class='CodeStyleStatusBarPanel']]"
            )
        )

    fun byContainsText(text: String): ComponentFixture =
        find<ComponentFixture>(byXpath("//div[contains(@text,'$text') and @text.key='biome.widget.version']"))

    val text: String
        get() = callJs("component.getText();")
}
