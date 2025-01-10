package com.github.biomejs.intellijbiome

import com.intellij.ide.IconProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon

class BiomeConfigIconProvider : IconProvider() {
    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val file = element as? PsiFile ?: return null
        if (!file.isValid || file.isDirectory) return null
        val virtualFile = file.virtualFile ?: return null

        // Check if the file is a valid Biome config file
        if (virtualFile.name.contains(BiomePackage.configName) &&
            BiomePackage.configValidExtensions.contains(virtualFile.extension)
        ) {
            return BiomeIcons.BiomeIcon
        }

        return null
    }
}
