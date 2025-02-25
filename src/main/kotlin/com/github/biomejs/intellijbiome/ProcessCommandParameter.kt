package com.github.biomejs.intellijbiome

import java.nio.file.Path

/**
 * A command argument for creating a process.
 */
sealed interface ProcessCommandParameter {
    /**
     * A raw string value.
     * No conversion will be applied on any environment.
     */
    class Value(val value: String) : ProcessCommandParameter {
        override fun toString() = value
    }

    /**
     * A file path value which points any file or directory on the execution environment.
     * For example, the path will be converted automatically when running Node.js on WSL 2.
     */
    class FilePath(val path: Path) : ProcessCommandParameter {
        override fun toString() = path.toString()
    }
}
