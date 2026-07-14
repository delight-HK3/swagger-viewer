package com.github.swaggerViewer.view

import com.github.swaggerViewer.service.yaml.SwaggerSpecDetector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Processor
import javax.swing.SwingConstants

class SwaggerViewerToolWindowFactory : ToolWindowFactory, DumbAware {

    // Entry point IntelliJ calls when the Tool Window opens.
    // Waits for indexing (Dumb Mode) to finish, scans the project in the background,
    // then builds the tabs on the EDT based on the scan result (whether Annotation/YAML files exist).
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Analyze the project content after indexing completes and build tabs dynamically.
        // FilenameIndex and PSI searches are slow operations — must run on a pooled thread, not EDT.
        DumbService.getInstance(project).runWhenSmart {
            ApplicationManager.getApplication().executeOnPooledThread {
                val hasAnnotations = ApplicationManager.getApplication().runReadAction<Boolean> {
                    hasSwaggerAnnotations(project)
                }
                val hasYaml = ApplicationManager.getApplication().runReadAction<Boolean> {
                    hasSwaggerYamlFiles(project)
                }
                ApplicationManager.getApplication().invokeLater {
                    addTabs(project, toolWindow, hasAnnotations, hasYaml)
                }
            }
        }
    }

    // Adds tabs to the Tool Window based on the scan result.
    // Adds an "Annotation" tab if annotation-based specs exist, and a "YAML" tab if
    // YAML/JSON spec files exist; if neither exists, shows only a single info label.
    private fun addTabs(
        project: Project,
        toolWindow: ToolWindow,
        hasAnnotations: Boolean,
        hasYaml: Boolean
    ) {
        val contentFactory = ContentFactory.getInstance()

        if (hasAnnotations) {
            val content = contentFactory.createContent(SwaggerViewerPanel(project), "Annotation", false)
            toolWindow.contentManager.addContent(content)
        }

        if (hasYaml) {
            val content = contentFactory.createContent(SwaggerViewerYamlPanel(project), "YAML", false)
            toolWindow.contentManager.addContent(content)
        }

        if (!hasAnnotations && !hasYaml) {
            val label = JBLabel("No Swagger-related files found.", SwingConstants.CENTER)
            val content = contentFactory.createContent(label, "", false)
            toolWindow.contentManager.addContent(content)
        }
    }

    // True if the project has at least one Java/Kotlin file importing swagger annotations or @RestController.
    // Uses the word index (not AnnotatedElementsSearch) so it works before Gradle sync adds library JARs
    // to the module classpath — AnnotatedElementsSearch requires the annotation class to be resolvable,
    // which fails if runWhenSmart fires before dependency JARs are indexed.
    private fun hasSwaggerAnnotations(project: Project): Boolean {
        val scope = GlobalSearchScope.projectScope(project)
        val searchHelper = PsiSearchHelper.getInstance(project)
        var found = false

        // "swagger" is a word token inside "io.swagger.v3.oas.annotations" import statements
        searchHelper.processAllFilesWithWord("swagger", scope, Processor { psiFile ->
            val ext = psiFile.virtualFile?.extension?.lowercase()
            if (ext == "java" || ext == "kt") { found = true; false } else true
        }, false)
        if (found) return true

        // Also show the Annotation tab for Spring MVC controllers without explicit swagger imports
        searchHelper.processAllFilesWithWord("RestController", scope, Processor { psiFile ->
            val ext = psiFile.virtualFile?.extension?.lowercase()
            if (ext == "java" || ext == "kt") { found = true; false } else true
        }, true)
        return found
    }

    // True if the project has at least one yaml/yml/json file recognized as an OpenAPI spec
    private fun hasSwaggerYamlFiles(project: Project): Boolean {
        val scope = GlobalSearchScope.projectScope(project)
        return listOf("yaml", "yml", "json").any { ext ->
            FilenameIndex.getAllFilesByExt(project, ext, scope)
                .any { SwaggerSpecDetector.isSwaggerOrOpenApiFile(it) }
        }
    }

    // Always exposes the Tool Window (shows the icon regardless of whether Annotation/YAML files exist;
    // the actual content check is done via a scan in createToolWindowContent).
    override fun shouldBeAvailable(project: Project): Boolean = true
}
