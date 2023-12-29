package com.github.biomejs.intellijbiome.utils

import com.intellij.remoterobot.RemoteRobot

fun RemoteRobot.isAvailable(): Boolean = runCatching {
    callJs<Boolean>("true")
}.getOrDefault(false)
