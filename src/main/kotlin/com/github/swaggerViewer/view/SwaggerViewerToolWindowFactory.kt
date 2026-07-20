package com.github.swaggerViewer.view

import com.github.swaggerViewer.service.yaml.SwaggerSpecDetector
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import javax.swing.SwingConstants
import kotlin.coroutines.resume

private val LOG = Logger.getInstance(SwaggerViewerToolWindowFactory::class.java)

/**
 * [step 01] Plugin entry point — detects what type of Swagger content the project contains
 * and creates the appropriate preview tab(s) in the Tool Window.
 *
 * Waits for IntelliJ's smart mode (index ready) before scanning, because PSI search APIs
 * ([PsiSearchHelper], [FilenameIndex]) require a complete index to return reliable results.
 *
 * Detection logic:
 *  - `.java` / `.kt` source files containing `"swagger"` or `"RestController"` keyword
 *    → adds **Annotation** tab → [SwaggerViewerPanel] [step 02-A]
 *  - `.yaml` / `.yml` / `.json` files identified as Swagger/OpenAPI specs by [SwaggerSpecDetector]
 *    → adds **YAML** tab → [SwaggerViewerYamlPanel] [step 02-Y]
 *
 * Both tabs can coexist if the project has both annotations and spec files.
 *
 * @see SwaggerViewerPanel
 * @see SwaggerViewerYamlPanel
 */
class SwaggerViewerToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val statusLabel = JBLabel("Initializing...", SwingConstants.CENTER).apply {
            font = JBUI.Fonts.label(14f)
        }

        val contentFactory = ContentFactory.getInstance()
        toolWindow.contentManager.removeAllContents(true)
        toolWindow.contentManager.addContent(contentFactory.createContent(statusLabel, "", false))

        fun setStatus(text: String) {
            ApplicationManager.getApplication().invokeLater({
                statusLabel.text = text
            }, ModalityState.any())
        }

        val parentDisposable = Disposable { }
        Disposer.register(toolWindow.disposable, parentDisposable)

        val toolWindowJob = SupervisorJob()
        // [수정 포인트 1] Dispatchers.EDT를 명확하게 사용할 수 있도록 상단 import와 연계 설정
        val toolWindowScope = CoroutineScope(Dispatchers.Default + toolWindowJob)

        Disposer.register(parentDisposable) {
            toolWindowJob.cancel("Tool window disposed")
        }

        toolWindowScope.launch {
            try {
                setStatus("Waiting for index...")

                project.awaitSmartMode()

                if (project.isDisposed) return@launch

                setStatus("Scanning code...")

                val (hasAnnotations, hasYaml) = try {
                    readAction {
                        if (project.isDisposed) {
                            false to false
                        } else {
                            hasSwaggerAnnotations(project) to hasSwaggerYamlFiles(project)
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("Project or Module disposed during Swagger index scanning.", e)
                    false to false
                }

                if (project.isDisposed) return@launch

                // [수정 포인트 2] Dispatchers.EDT를 직접 명시하여 UI 스레드로 안전하게 전환
                withContext(Dispatchers.EDT) {
                    if (!project.isDisposed) {
                        addTabs(project, toolWindow, hasAnnotations, hasYaml)
                    }
                }
            } catch (e: CancellationException) {
                LOG.info("Swagger viewer initialization was cancelled.")
            } catch (t: Throwable) {
                LOG.error("Failed to initialize Swagger Viewer", t)
                withContext(Dispatchers.EDT) {
                    if (!project.isDisposed) {
                        showLoadingOrMessage(toolWindow, "Initialization Error: ${t.message}")
                    }
                }
            }
        }
    }

    private suspend fun Project.awaitSmartMode() {
        if (!DumbService.isDumb(this)) return

        suspendCancellableCoroutine<Unit> { continuation ->
            DumbService.getInstance(this).runWhenSmart {
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    private fun addTabs(project: Project, toolWindow: ToolWindow, hasAnnotations: Boolean, hasYaml: Boolean) {
        toolWindow.contentManager.removeAllContents(true)
        val contentFactory = ContentFactory.getInstance()

        if (hasAnnotations) {
            toolWindow.contentManager.addContent(
                contentFactory.createContent(SwaggerViewerPanel(project), "Annotation", false)
            )
        }
        if (hasYaml) {
            toolWindow.contentManager.addContent(
                // [수정 포인트 3] SwaggerViewerYamlPanel 클래스 정의에 맞게
                // 불필요한 파라미터(assetsDir)를 지우고 오직 project만 단독으로 넘기도록 일치시켰습니다.
                contentFactory.createContent(SwaggerViewerYamlPanel(project), "YAML", false)
            )
        }

        if (!hasAnnotations && !hasYaml) {
            val label = JBLabel("No Swagger-related files found.", SwingConstants.CENTER)
            toolWindow.contentManager.addContent(contentFactory.createContent(label, "", false))
        }
    }

    private fun showLoadingOrMessage(toolWindow: ToolWindow, message: String) {
        val contentFactory = ContentFactory.getInstance()
        val label = JBLabel(message, SwingConstants.CENTER)
        toolWindow.contentManager.removeAllContents(true)
        toolWindow.contentManager.addContent(contentFactory.createContent(label, "", false))
    }

    private fun hasSwaggerAnnotations(project: Project): Boolean {
        val scope = GlobalSearchScope.projectScope(project)
        val searchHelper = PsiSearchHelper.getInstance(project)
        var found = false

        searchHelper.processAllFilesWithWord("swagger", scope, { psiFile ->
            val ext = psiFile.virtualFile?.extension?.lowercase()
            if (ext == "java" || ext == "kt") { found = true; false } else true
        }, false)

        if (!found) {
            searchHelper.processAllFilesWithWord("RestController", scope, { psiFile ->
                val ext = psiFile.virtualFile?.extension?.lowercase()
                if (ext == "java" || ext == "kt") { found = true; false } else true
            }, true)
        }
        return found
    }

    private fun hasSwaggerYamlFiles(project: Project): Boolean {
        val scope = GlobalSearchScope.projectScope(project)
        return listOf("yaml", "yml", "json").any { ext ->
            FilenameIndex.getAllFilesByExt(project, ext, scope)
                .any { SwaggerSpecDetector.isSwaggerOrOpenApiFile(it) }
        }
    }
}