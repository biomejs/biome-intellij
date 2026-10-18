package com.github.biomejs.intellijbiome.schema

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.jetbrains.jsonSchema.extension.ContentAwareJsonSchemaFileProvider

/** Last-resort association for a manually selected, custom-named configuration. */
class BiomeCustomConfigSchemaProvider : ContentAwareJsonSchemaFileProvider {
    override fun getSchemaFile(file: PsiFile): VirtualFile? {
        val json = file as? JsonFile ?: return null
        // Presence matters: an unresolved, empty or invalid explicit schema still belongs to the user.
        if ((json.topLevelValue as? JsonObject)?.findProperty("\$schema") != null) return null
        val config = file.originalFile.virtualFile?.takeIf { it.isValid } ?: return null
        return file.project.getServiceIfCreated(BiomeSchemaAssociationService::class.java)?.schemaFor(config)
    }
}
