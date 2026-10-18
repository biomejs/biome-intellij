package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.services.BiomeOnTypeFormattingService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile

internal class BiomeOnTypeHandler : TypedHandlerDelegate() {
    override fun newTypingStarted(c: Char, editor: Editor, context: DataContext) {
        editor.putUserData(OVERTYPE, null)
        editor.putUserData(SELECTION_REPLACEMENT, null)
        if (editor.selectionModel.hasSelection()) {
            // charTyped runs after the IDE removes the selection. Keep this marker for
            // every caret callback, and clear it even if another delegate consumes input.
            val replacement = Any()
            editor.putUserData(SELECTION_REPLACEMENT, replacement)
            ApplicationManager.getApplication().invokeLater {
                if (!editor.isDisposed && editor.getUserData(SELECTION_REPLACEMENT) === replacement) {
                    editor.putUserData(SELECTION_REPLACEMENT, null)
                }
            }
            return
        }
        val project = editor.project ?: return
        if (project.isDisposed || editor.isDisposed || !BiomeSettings.getInstance(project).formatOnType ||
            editor.caretModel.caretCount != 1 ||
            LookupManager.getActiveLookup(editor) != null || TemplateManager.getInstance(project).getActiveTemplate(editor) != null) return
        val offset = editor.caretModel.offset
        val document = editor.document
        if (offset >= document.textLength || document.charsSequence[offset] != c) return
        val lifecycle = project.service<BiomeLspLifecycleService>()
        val candidate = Overtype(document.modificationStamp, offset, lifecycle.requestStamp())
        editor.putUserData(OVERTYPE, candidate)
        // Skipping an existing closing character bypasses charTyped. Observe only an
        // unchanged document with the caret advanced exactly across that character.
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || editor.isDisposed || editor.getUserData(OVERTYPE) !== candidate) return@invokeLater
            editor.putUserData(OVERTYPE, null)
            if (document.modificationStamp == candidate.stamp && editor.caretModel.offset == candidate.offset + 1 &&
                lifecycle.isCurrent(candidate.generation)) project.service<BiomeOnTypeFormattingService>().onTyped(editor, c)
        }
    }

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        editor.putUserData(OVERTYPE, null)
        if (editor.getUserData(SELECTION_REPLACEMENT) == null) {
            project.service<BiomeOnTypeFormattingService>().onTyped(editor, c)
        }
        return Result.CONTINUE
    }

    private data class Overtype(val stamp: Long, val offset: Int, val generation: Long)
    private companion object {
        val OVERTYPE = Key.create<Overtype>("Biome.onType.overtype")
        val SELECTION_REPLACEMENT = Key.create<Any>("Biome.onType.selectionReplacement")
    }
}

internal class BiomeOnTypeEnterHandler : EnterHandlerDelegate {
    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        file.project.service<BiomeOnTypeFormattingService>().onTyped(editor, '\n')
        return EnterHandlerDelegate.Result.Continue
    }
}
