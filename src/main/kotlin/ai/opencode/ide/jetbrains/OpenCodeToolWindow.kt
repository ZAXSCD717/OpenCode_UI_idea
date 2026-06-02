package ai.opencode.ide.jetbrains

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener

import com.intellij.ui.content.ContentFactory
import java.awt.CardLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * OpenCode sidebar icon (ToolWindow).
 *
 * Clicking the sidebar icon toggles the toolwindow panel that contains
 * the OpenCode terminal (ShellTerminalWidget) or Web UI (JBCefBrowser).
 *
 * No file-editor tabs are created — content is embedded directly in the panel.
 */
class OpenCodeToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<OpenCodeService>()

        // Create content panel with CardLayout (terminal / web / empty)
        val panel = JPanel(CardLayout())
        val emptyLabel = JLabel(
            "<html><center><h3>OpenCode</h3><p>Press <b>Ctrl+\\</b> (Win/Linux) or <b>Cmd+Esc</b> (Mac) to connect.</p></center></html>",
            SwingConstants.CENTER
        )
        panel.add(emptyLabel, "empty")

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // Register references in service for content population
        service.attachToolWindow(panel, toolWindow)

        // Listen for show events and state changes to manage content lifecycle.
        // Use stateChanged() to detect hide (check isVisible inside).
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(tw: ToolWindow) {
                    if (tw.id == "OpenCode") {
                        service.onToolWindowOpened()
                    }
                }

                override fun stateChanged() {
                    val tw = toolWindow
                    if (tw != null && tw.id == "OpenCode" && !tw.isVisible) {
                        service.onToolWindowClosed()
                    }
                }
            }
        )
    }
}
