package com.example.swaggerViewer.view

import com.example.swaggerViewer.service.SwaggerSpecDetector
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * .yaml/.yml/.json 파일이면서 OpenAPI/Swagger 스펙으로 보이는 경우에만 분할 에디터를 제공한다.
 * 일반 YAML/JSON 파일에는 끼어들지 않는다.
 */
class SwaggerSplitEditorProvider : FileEditorProvider, DumbAware {

    private val textEditorProvider = TextEditorProvider.getInstance()

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return SwaggerSpecDetector.isSwaggerOrOpenApiFile(file)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = textEditorProvider.createEditor(project, file)
            as com.intellij.openapi.fileEditor.TextEditor
        val previewEditor = SwaggerPreviewFileEditor(project, file)
        return SwaggerSplitEditor(textEditor, previewEditor)
    }

    override fun getEditorTypeId(): String = "swagger-split-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class SwaggerSplitEditor(
    textEditor: com.intellij.openapi.fileEditor.TextEditor,
    previewEditor: SwaggerPreviewFileEditor
) : TextEditorWithPreview(
    textEditor,
    previewEditor,
    "Swagger Preview",
    Layout.SHOW_EDITOR_AND_PREVIEW
)
