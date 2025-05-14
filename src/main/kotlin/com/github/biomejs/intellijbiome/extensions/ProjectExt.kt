package com.github.biomejs.intellijbiome.extensions

import com.github.biomejs.intellijbiome.BiomeConfig
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope


fun Project.findBiomeConfigs(): List<VirtualFile> {
    val scope = GlobalSearchScope.projectScope(this)
    return BiomeConfig.validFileNames.flatMap { name ->
        FilenameIndex.getVirtualFilesByName(name, scope)
    }
}
