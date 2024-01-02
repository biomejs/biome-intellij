package com.github.biomejs.intellijbiome.extensions

import java.io.BufferedReader
import java.io.File
import java.io.FileReader

fun File.isNodeScript(): Boolean {
    val reader = BufferedReader(FileReader(this))
    val line = reader.readLine()
    return line.startsWith("#!/usr/bin/env node")
}

