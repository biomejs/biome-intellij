package com.github.biomejs.intellijbiome.lsp

/** The settings applied to the workspace by the LSP */
data class BiomeLspWorkspaceSettings(
    /** Unstable features enabled */
    var unstable: Boolean? = null,

    /** Only run Biome if a `biome.json` configuration file exists. */
    var requireConfiguration: Boolean? = null,

    /** Path to the configuration file to prefer over the default `biome.json`. */
    var configurationPath: String? = null,

    /** Experimental settings */
    var experimental: ExperimentalSettings? = null,
) {
    data class ExperimentalSettings(
        /** Enable experimental symbol renaming */
        var rename: Boolean? = null,
    )
}
