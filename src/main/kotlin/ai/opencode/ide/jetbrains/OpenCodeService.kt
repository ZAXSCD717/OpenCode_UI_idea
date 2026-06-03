package ai.opencode.ide.jetbrains

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.SseEventListener
import ai.opencode.ide.jetbrains.api.models.*
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.session.SessionManager
import ai.opencode.ide.jetbrains.session.TurnSnapshot
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalFileEditorProvider
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalLinkFilter
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalVirtualFile
import com.intellij.openapi.fileEditor.FileEditorManager
import ai.opencode.ide.jetbrains.ui.OpenCodeConnectDialog
import ai.opencode.ide.jetbrains.util.PathUtil
import ai.opencode.ide.jetbrains.util.PortFinder
import ai.opencode.ide.jetbrains.util.ProcessAuthDetector
import ai.opencode.ide.jetbrains.web.WebModeSupport

import com.google.gson.JsonElement
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.SystemNotifications

import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.ui.TerminalPanel
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalView

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.KeyEvent
import javax.swing.JPanel

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class OpenCodeService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(OpenCodeService::class.java)

    private enum class ConnectionMode { NONE, TERMINAL, WEB, REMOTE }

    companion object {
        private const val OPEN_CODE_TAB_PREFIX = "OpenCode"
        private const val RETRY_INTERVAL_MS = 5000L
        private const val BARRIER_TIMEOUT_MS = 2000L
        internal var DEBOUNCE_MS = 1500L

        // ToolWindow panel card names (CardLayout)
        internal const val CARD_EMPTY = "empty"
        internal const val CARD_TERMINAL = "terminal"
        internal const val CARD_WEB = "web"
    }

    private var hostname: String = "127.0.0.1"
    private var port: Int? = null
    private var username: String? = null
    private var password: String? = null
    private var apiClient: OpenCodeApiClient? = null
    private var sseListener: SseEventListener? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var lastMode: ConnectionMode = ConnectionMode.NONE
    private var wasEverConnected = false
    private var remoteReconnectFailures = 0
    private var remoteReconnectDialogShown = false

    // ToolWindow panel references (no file-editor tabs)
    private var toolWindow: ToolWindow? = null
    private var toolWindowPanel: JPanel? = null
    private var terminalWidget: ShellTerminalWidget? = null
    private var webBrowser: JBCefBrowser? = null
    // Virtual file for the terminal editor tab (original approach — works reliably)
    private var terminalVirtualFile: ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalVirtualFile? = null

    private val connectionListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var connectionManagerTask: ScheduledFuture<*>? = null
    @Volatile private var lastIdleNotification: Notification? = null
    @Volatile private var autoConnectAttempted = false

    
    // Turn state: keyed by sessionId
    private val turnMessageIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val turnPendingPayloads = java.util.concurrent.ConcurrentHashMap<String, List<FileDiff>>()
    private val turnSnapshots = java.util.concurrent.ConcurrentHashMap<String, TurnSnapshot>()
    private val turnIdleWaiting = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val turnBarrierTasks = java.util.concurrent.ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val turnLastTriggerTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    val sessionManager: SessionManager get() = _sessionManager ?: project.service()
    private val diffViewerService: DiffViewerService get() = _diffViewerService ?: project.service()

    // Test hooks
    private var _sessionManager: SessionManager? = null
    private var _diffViewerService: DiffViewerService? = null

    internal fun setTestDeps(sm: SessionManager, dvs: DiffViewerService, client: OpenCodeApiClient) {
        _sessionManager = sm
        _diffViewerService = dvs
        apiClient = client
    }
    
    internal var invokeLater: (Runnable) -> Unit = { 
        ApplicationManager.getApplication().invokeLater(it) 
    }

    // ==================== ToolWindow Panel Management ====================

    /**
     * Attach the ToolWindow panel and toolwindow reference.
     * Called by OpenCodeToolWindowFactory on creation.
     */
    fun attachToolWindow(panel: JPanel, tw: ToolWindow) {
        toolWindowPanel = panel
        toolWindow = tw
        // Start with empty card
        showCard(CARD_EMPTY)
    }

    /**
     * Called when the ToolWindow is shown (sidebar icon clicked / toggled visible).
     * If we already have content (terminal or web), re-embed it in the panel.
     * Otherwise, if the service has a stored connection, try to restore the UI.
     */
    fun onToolWindowOpened() {
        when {
            terminalWidget != null -> showContentInToolWindow()
            webBrowser != null -> showContentInToolWindow()
            port != null && isConnected.get() -> restoreUiForMode()
            // No content yet — quick TCP check to decide: auto-connect to existing server
            // or auto-start a new terminal session.
            else -> {
                val checkPort = port ?: 4096
                val tcpOpen = try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("127.0.0.1", checkPort), 100)
                        true
                    }
                } catch (_: Exception) { false }
                if (tcpOpen) {
                    // Something is listening — try to auto-connect as OpenCode server
                    showCard(CARD_EMPTY)
                    tryAutoConnectInBackground()
                } else {
                    // Nothing running — full startup via createLocalTerminal (includes API client + connection manager)
                    if (port == null) port = 4096
                    createLocalTerminal(hostname, port!!, password)
                }
            }
        }
    }

    /**
     * Try to detect a running OpenCode server on the default port (4096)
     * and auto-connect to populate the toolwindow panel.
     * Only runs once per session to avoid repeated scanning.
     */
    private fun tryAutoConnectInBackground() {
        if (project.isDisposed || autoConnectAttempted) return
        autoConnectAttempted = true
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                val defaultPort = 4096
                // Quick TCP check (100ms) before HTTP health check (5s)
                val tcpOpen = try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("127.0.0.1", defaultPort), 100)
                        true
                    }
                } catch (_: Exception) { false }
                if (!tcpOpen) {
                    logger.info("[OpenCode] Auto-connect: nothing listening on port $defaultPort")
                    return@submit
                }
                if (!PortFinder.isOpenCodeRunningOnPort(defaultPort)) {
                    logger.info("[OpenCode] Auto-connect: port $defaultPort is not an OpenCode server")
                    return@submit
                }

                val bin = detectOpenCodeBinary()
                if (bin == null) {
                    logger.info("[OpenCode] Auto-connect skipped: OpenCode CLI not found")
                    return@submit
                }

                val auth = ProcessAuthDetector.detectAuthForPort(defaultPort)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || hasTerminalUI() || isConnected.get()) return@invokeLater
                    logger.info("[OpenCode] Auto-connected to running server on port $defaultPort (bin=$bin)")
                    hostname = "127.0.0.1"
                    port = defaultPort
                    username = auth.username
                    password = auth.password
                    lastMode = ConnectionMode.TERMINAL
                    initializeApiClient(hostname, defaultPort)
                    startConnectionManager()
                    // Use the established focusOrCreateTerminal path (same as shortcut)
                    focusOrCreateTerminal(interactive = false)
                }
            } catch (e: Exception) {
                logger.debug("[OpenCode] Auto-connect failed: ${e.message}")
            }
        }
    }

    /**
     * Called when the ToolWindow is hidden.
     * Keep the session alive — do NOT dispose the widget or browser.
     */
    fun onToolWindowClosed() {
        // Session stays alive. Widget/browser kept in memory.
        // The terminal process continues running.
    }

    /**
     * Focus the terminal or web content.
     * - Terminal: embed the widget in the ToolWindow panel
     * - Web: embed the browser in the toolwindow panel
     */
    private fun showContentInToolWindow() {
        val panel = toolWindowPanel ?: return
        panel.removeAll()
        when {
            terminalWidget != null -> {
                showTerminalInToolWindow(terminalWidget!!)
                // Request focus so keyboard input reaches the terminal shell
                terminalWidget!!.preferredFocusableComponent?.requestFocusInWindow()
            }
            webBrowser != null -> {
                panel.add(webBrowser!!.component, CARD_WEB)
                showCard(CARD_WEB)
            }
            else -> showCard(CARD_EMPTY)
        }
        panel.revalidate()
        panel.repaint()
    }

    private fun showTerminalInToolWindow(w: ShellTerminalWidget) {
        val panel = toolWindowPanel ?: return
        panel.add(w.component, CARD_TERMINAL)
        showCard(CARD_TERMINAL)
        panel.revalidate()
        panel.repaint()
        logger.warn("[OpenCode] Terminal widget parent=${w.component.parent?.javaClass?.name} panel=${panel.javaClass.name}")
        // Request focus so keyboard input reaches the terminal shell
        w.preferredFocusableComponent?.requestFocusInWindow()
        
        // Install resize listener for when the ToolWindow panel resizes.
        // JediTerm needs to know the new size to recalculate columns/rows.
        // Without this, the terminal content might not render if the initial
        // layout happens before the ToolWindow has a proper size.
        if (panel.getComponentListeners().none { it is TerminalResizeListener }) {
            panel.addComponentListener(TerminalResizeListener(w))
            logger.warn("[OpenCode] TerminalResizeListener installed")
        }
        
        // Deferred layout: after the ToolWindow is fully laid out, ensure the
        // terminal widget has a proper size and its rendering pipeline is triggered.
        // JediTerm's TerminalPanel needs a non-zero size to calculate columns/rows;
        // without it, TUI output (escape sequences) is processed for 0-width terminal
        // and displayed as blank.
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && w.component.isShowing) {
                val size = panel.size
                logger.warn("[OpenCode] Terminal deferred layout: panel=${size.width}x${size.height} widget=${w.component.size}")
                if (size.width > 0 && size.height > 0) {
                    w.component.setSize(size)
                    w.component.revalidate()
                    w.component.repaint()
                } else {
                    logger.warn("[OpenCode] Terminal deferred layout skipped: panel has zero size")
                }
            }
        }
    }
    
    /** ComponentListener that propagates ToolWindow panel resize to the terminal widget. */
    private class TerminalResizeListener(private val widget: ShellTerminalWidget) : java.awt.event.ComponentAdapter() {
        override fun componentResized(e: java.awt.event.ComponentEvent) {
            val size = e.component.size
            if (size.width > 0 && size.height > 0) {
                widget.component.setSize(size)
                widget.component.revalidate()
                widget.component.repaint()
            }
        }
    }

    private fun showCard(card: String) {
        val layout = toolWindowPanel?.layout
        if (layout is CardLayout) {
            layout.show(toolWindowPanel, card)
        }
    }

    // ==================== Public API ====================

    fun focusOrCreateTerminal(interactive: Boolean = false) {
        val uiOpen = hasTerminalUI()
        val running = port?.let { PortFinder.isOpenCodeRunningOnPort(it, hostname, username, password) } ?: false
        if (isConnected.get() && !running) isConnected.set(false)

        when {
            isConnected.get() && uiOpen -> toolWindow?.show()
            interactive && port != null -> showReconnectOrNewDialog(running)
            !interactive && running && port != null -> restoreUiForMode()
            !interactive && !running && port != null -> showDisconnectedDialog(false)
            else -> showConnectionDialog()
        }
    }

    fun hasTerminalUI(): Boolean {
        return terminalWidget != null || webBrowser != null
    }
    
    private fun showDisconnectedDialog(interactive: Boolean) {
        if (!interactive || lastMode == ConnectionMode.NONE) return
        ApplicationManager.getApplication().invokeLater {
            val res = Messages.showYesNoCancelDialog(
                project,
                "Server at $hostname:$port not running. Restart?",
                "OpenCode",
                "Restart",
                "New",
                "Cancel",
                Messages.getWarningIcon()
            )
            if (res == Messages.YES) restartServer(lastMode)
            else if (res == Messages.NO) {
                disconnectAndReset()
                showConnectionDialog()
            }
        }
    }

    fun focusOrCreateTerminalAndPaste(text: String) {
        if (text.isBlank() || project.isDisposed) return
        if (apiClient == null && terminalWidget == null && webBrowser == null) { ApplicationManager.getApplication().invokeLater { Messages.showInfoMessage(project, "OpenCode not running.", "OpenCode") }; return }
        schedulePasteAttempt(text, 20, 100L)
        ApplicationManager.getApplication().invokeLater { focusTerminalUI() }
    }

    fun pasteToTerminal(text: String): Boolean {
        if (text.isBlank()) return false
        // Prefer API client if available (older opencode with HTTP server)
        apiClient?.let { client -> 
            AppExecutorUtil.getAppExecutorService().submit { 
                try { 
                    if (!client.tuiAppendPrompt(text)) logger.warn("[Paste] API failed to append prompt")
                } catch (e: Exception) { 
                    logger.warn("[Paste] API error: ${e.message}") 
                } 
            }
            return true 
        }
        // Fallback: write directly to the terminal widget's TTY (opencode v1.15+ TUI mode)
        val widget = terminalWidget
        if (widget != null) {
            AppExecutorUtil.getAppExecutorService().submit {
                try {
                    val connector = widget.ttyConnector
                    if (connector != null) {
                        connector.write(text)
                        connector.write("\n")
                        logger.warn("[Paste] Written to TTY connector: $text")
                    }
                } catch (e: Exception) {
                    logger.warn("[Paste] TTY write error: ${e.message}")
                }
            }
            return true
        }
        return false
    }

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener); listener(isConnected.get())
    }

    fun removeConnectionListener(listener: (Boolean) -> Unit) { connectionListeners.remove(listener) }

    fun onTerminalDisposed() {
        if (isConnected.get() || port != null) {
            logger.info("[OpenCode] Terminal disposed (timeout or closed). Resetting connection state.")
            disconnectAndReset()
        }
    }

    // ==================== Event Handling & Barrier ====================

    private fun handleEvent(event: OpenCodeEvent) {
        if (event !is MessagePartUpdatedEvent && event !is UnknownEvent) logger.info("[OpenCode] SSE: ${event.type}")
        when (event) {
            is SessionStatusEvent -> {
                val sId = event.properties.sessionID
                val status = event.properties.status
                if (status.isBusy()) {
                    val started = sessionManager.onTurnStart()
                    if (started) clearTurnState(sId)
                } else if (status.isIdle()) {
                    // Capture snapshot BEFORE attempting barrier
                    val snapshot = sessionManager.onTurnEnd()
                    if (snapshot != null) {
                        turnSnapshots[sId] = snapshot
                        logger.info("[OpenCode] Turn #${snapshot.turnNumber} snapshot captured")
                        sendNotification(
                            "OpenCode Task Completed",
                            "Session is now idle. Checking for changes...",
                            replacePrevious = true
                        )
                    }
                    turnIdleWaiting[sId] = true
                    attemptBarrierTrigger(sId)
                }
            }
            is SessionIdleEvent -> {
                val sId = event.properties.sessionID
                val snapshot = sessionManager.onTurnEnd()
                if (snapshot != null) {
                    turnSnapshots[sId] = snapshot
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} snapshot captured (via idle event)")
                    sendNotification(
                        "OpenCode Task Completed",
                        "Session is now idle. Checking for changes...",
                        replacePrevious = true
                    )
                }
                turnIdleWaiting[sId] = true
                attemptBarrierTrigger(sId)
            }
            is FileEditedEvent -> sessionManager.onFileEdited(event.properties.file)
            is MessageUpdatedEvent -> {
                val info = event.properties.info
                if (info.role == null || info.role == "assistant") {
                    recordTurnMessageId(info.sessionID, info.id)
                }
            }
            is MessagePartUpdatedEvent -> extractPartMessageInfo(event.properties.part)?.let { 
                recordTurnMessageId(it.sessionId, it.messageId) 
            }
            is CommandExecutedEvent -> recordTurnMessageId(event.properties.sessionID, event.properties.messageID)
            is SessionDiffEvent -> if (event.properties.diff.isNotEmpty()) {
                turnPendingPayloads[event.properties.sessionID] = event.properties.diff
                attemptBarrierTrigger(event.properties.sessionID)
            }
            else -> {}
        }
    }

    private fun clearTurnState(sessionId: String) {
        val oldSnapshot = turnSnapshots.remove(sessionId)
        turnMessageIds.remove(sessionId)
        turnPendingPayloads.remove(sessionId)
        turnIdleWaiting.remove(sessionId)
        turnBarrierTasks.remove(sessionId)?.cancel(false)
        turnLastTriggerTimes.remove(sessionId)
        
        if (oldSnapshot != null) {
            logger.info("[OpenCode] Turn state cleared (was Turn #${oldSnapshot.turnNumber})")
        } else {
            logger.info("[OpenCode] Turn state cleared (no previous snapshot)")
        }
    }

    private fun recordTurnMessageId(sessionId: String, messageId: String) {
        if (turnMessageIds.containsKey(sessionId)) return
        turnMessageIds[sessionId] = messageId
        val turnNum = turnSnapshots[sessionId]?.turnNumber ?: sessionManager.getCurrentTurnNumber()
        logger.info("[OpenCode] Turn #$turnNum MessageID: $messageId")
        attemptBarrierTrigger(sessionId)
    }

    private fun attemptBarrierTrigger(sessionId: String) {
        val ready = turnIdleWaiting[sessionId] == true
        val hasId = turnMessageIds.containsKey(sessionId)
        val hasPayload = turnPendingPayloads.containsKey(sessionId)
        val snapshot = turnSnapshots[sessionId]
        
        logger.debug("[OpenCode] Barrier check: ready=$ready, hasId=$hasId, hasPayload=$hasPayload, hasSnapshot=${snapshot != null}")
        
        if (ready && (hasId || hasPayload)) {
            turnIdleWaiting[sessionId] = false
            turnBarrierTasks.remove(sessionId)?.cancel(false)
            if (snapshot != null) {
                logger.info("[OpenCode] Turn #${snapshot.turnNumber} Barrier triggered → fetching diffs")
                triggerDiffFetch(sessionId, snapshot)
            } else {
                logger.warn("[OpenCode] Barrier triggered but no snapshot available!")
            }
        } else if (ready) {
            scheduleBarrierTimeout(sessionId)
        }
    }

    private fun scheduleBarrierTimeout(sessionId: String) {
        if (turnBarrierTasks.containsKey(sessionId)) return
        val turnNum = turnSnapshots[sessionId]?.turnNumber ?: "?"
        logger.debug("[OpenCode] Turn #$turnNum Barrier timeout scheduled (${BARRIER_TIMEOUT_MS}ms)")
        val task = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            val snapshot = turnSnapshots[sessionId]
            if (turnIdleWaiting[sessionId] == true && snapshot != null) {
                logger.warn("[OpenCode] Turn #${snapshot.turnNumber} Barrier timeout → forcing diff fetch")
                turnIdleWaiting[sessionId] = false
                triggerDiffFetch(sessionId, snapshot)
            }
            turnBarrierTasks.remove(sessionId)
        }, BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        turnBarrierTasks[sessionId] = task
    }

    private fun triggerDiffFetch(sessionId: String, snapshot: TurnSnapshot) {
        val now = System.currentTimeMillis()
        if (now - (turnLastTriggerTimes[sessionId] ?: 0) < DEBOUNCE_MS) {
            logger.debug("[OpenCode] Turn #${snapshot.turnNumber} Debounced (too soon)")
            return
        }
        turnLastTriggerTimes[sessionId] = now
        fetchAndShowDiffs(sessionId, snapshot)
    }

    private fun forceVfsRefresh(diffs: List<FileDiff>) {
        if (diffs.isEmpty()) return
        try {
            val files = diffs.mapNotNull { diff -> 
                PathUtil.resolveProjectPath(project, diff.file)?.let { 
                    val ioFile = java.io.File(it)
                    // If file is deleted, we must refresh the parent directory to detect deletion
                    if (!ioFile.exists()) ioFile.parentFile else ioFile
                } 
            }
            if (files.isNotEmpty()) {
                logger.info("[OpenCode] Forcing VFS refresh for ${files.size} paths: ${files.map { it.absolutePath }}")
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshIoFiles(files, false, false, null)
            }
        } catch (e: Exception) {
            logger.debug("[OpenCode] VFS refresh skipped: ${e.message}")
        }
    }

    private fun fetchAndShowDiffs(sessionId: String, snapshot: TurnSnapshot) {
        val client = apiClient ?: return
        val path = project.basePath ?: return
        val messageId = turnMessageIds[sessionId]
        val payload = turnPendingPayloads[sessionId]
        
        logger.info("[OpenCode] Turn #${snapshot.turnNumber} fetchAndShowDiffs: messageId=$messageId, payloadSize=${payload?.size ?: 0}")
        
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                var diffs: List<FileDiff> = emptyList()
                
                // Priority 1: Fetch by messageId (most accurate)
                if (messageId != null) {
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} Fetching diffs for messageId: $messageId")
                    diffs = client.getSessionDiff(sessionId, path, messageId)
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} Server returned ${diffs.size} diffs: ${diffs.map { "${it.file}(+${it.additions}/-${it.deletions})" }}")
                }
                
                // Priority 2: Use cached SSE payload
                if (diffs.isEmpty() && payload != null) {
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} Using SSE payload (${payload.size} files): ${payload.map { it.file }}")
                    diffs = payload
                }
                
                // Priority 3: Fallback to session summary (last resort)
                if (diffs.isEmpty() && messageId == null) {
                    logger.warn("[OpenCode] Turn #${snapshot.turnNumber} No messageId or payload, trying session summary")
                    client.getSession(sessionId, path)?.summary?.diffs?.let { diffs = it }
                }
                
                // 1. Force VFS refresh for Server files and Known files BEFORE processing
                // This ensures we catch deletions of files that Server missed but AI previously touched.
                // We rely on Server Authoritative logic: if Server missed a file and we have no history of it, we skip it.
                val serverFiles = diffs.map { it.file }
                val knownFiles = sessionManager.getKnownFilePaths()
                val filesToRefresh = (serverFiles + knownFiles).distinct()
                
                if (filesToRefresh.isNotEmpty()) {
                    // Create dummy FileDiffs just to pass the filename
                    forceVfsRefresh(filesToRefresh.map { FileDiff(it, "", "", 0, 0) })
                }
                
                // Capture any late VFS events triggered by the refresh
                val lateVfsEvents = sessionManager.getLiveVfsChangedFiles()
                val lateAiCreatedFiles = sessionManager.getLiveAiCreatedFiles()

                // Process using the snapshot via SessionManager (centralized logic)
                // Pass late VFS events to help with affinity checks
                val entries = sessionManager.getProcessedDiffs(diffs, snapshot, lateVfsEvents, lateAiCreatedFiles)
                
                if (entries.isNotEmpty()) {
                    sessionManager.updateKnownState(entries.map { it.file })
                    
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} Showing ${entries.size} diffs")
                    invokeLater {
                        if (!project.isDisposed) diffViewerService.showMultiFileDiff(entries)
                    }
                } else {
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} No diffs to show after processing.")
                }
            } catch (e: Exception) {
                logger.error("[OpenCode] Turn #${snapshot.turnNumber} Diff fetch error", e)
            } finally {
                turnPendingPayloads.remove(sessionId)
            }
        }
    }
    
    // createSyntheticDiff removed - logic moved to SessionManager


    // ==================== Lifecycle & Connection ====================

    private fun initializeApiClient(host: String, port: Int) {
        val apiHost = if (host == "0.0.0.0") "127.0.0.1" else host
        apiClient = OpenCodeApiClient(apiHost, port, username, password)
    }

    private fun startConnectionManager() {
        if (connectionManagerTask?.isDone == false || port == null || apiClient == null) return
        connectionManagerTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ if (!project.isDisposed) tryConnect() }, 0, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun tryConnect() {
        if (isConnected.get() || !isConnecting.compareAndSet(false, true)) return
        try {
            apiClient?.let {
                if (it.checkHealth(project.basePath!!)) {
                    remoteReconnectFailures = 0
                    wasEverConnected = true
                    connectToSse()
                } else {
                    handleConnectionFailure()
                }
            }
        } catch (_: Exception) {
            handleConnectionFailure()
        } finally {
            isConnecting.set(false)
        }
    }

    private fun handleConnectionFailure() {
        if (lastMode == ConnectionMode.NONE || !wasEverConnected) return
        if (++remoteReconnectFailures >= 2 && !remoteReconnectDialogShown) { remoteReconnectDialogShown = true; showRemoteReconnectDialog() }
    }

    private fun showRemoteReconnectDialog() {
        ApplicationManager.getApplication().invokeLater { if (isConnected.get()) return@invokeLater; if (Messages.showYesNoCancelDialog(project, "Connection lost. Reconnect?", "OpenCode", "Reconnect", "New", "Cancel", Messages.getWarningIcon()) == Messages.NO) { disconnectAndReset(); showConnectionDialog() } }
    }

    private fun connectToSse() {
        sseListener?.disconnect(); apiClient?.let { sseListener = it.createEventListener(project.basePath!!, { handleEvent(it) }, { updateConnectionState(false) }, { updateConnectionState(true) }, { updateConnectionState(false) }).apply { connect() } }
    }

    private fun updateConnectionState(connected: Boolean) { if (isConnected.getAndSet(connected) != connected) connectionListeners.forEach { it(connected) } }
    private fun sendNotification(
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION,
        replacePrevious: Boolean = false
    ) {
        val time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val message = "[$time] $content"
        invokeLater {
            if (project.isDisposed) return@invokeLater
            if (replacePrevious) lastIdleNotification?.expire()
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("OpenCode")
                .createNotification(title, message, type)
                .setImportant(true)
            if (replacePrevious) lastIdleNotification = notification
            notification.notify(project)
            try {
                SystemNotifications.getInstance().notify("OpenCode", title, message)
            } catch (e: Throwable) {
                logger.debug("[OpenCode] System notification failed: ${e.message}")
            }
        }
    }

    override fun dispose() {
        disconnectAndReset()
        OpenCodeTerminalFileEditorProvider.clearAll()
    }

    private fun disconnectAndReset() {
        connectionManagerTask?.cancel(true); sseListener?.disconnect(); isConnected.set(false); isConnecting.set(false)
        turnMessageIds.clear(); turnPendingPayloads.clear(); turnSnapshots.clear(); turnIdleWaiting.clear(); turnBarrierTasks.values.forEach { it.cancel(false) }; turnBarrierTasks.clear()
        terminateProcess()
        // Close the editor tab if open
        terminalVirtualFile?.let { vf ->
            if (FileEditorManager.getInstance(project).isFileOpen(vf)) {
                FileEditorManager.getInstance(project).closeFile(vf)
            }
            OpenCodeTerminalFileEditorProvider.unregisterWidget(vf)
        }
        terminalWidget = null
        terminalVirtualFile = null
        webBrowser?.let { Disposer.dispose(it) }
        webBrowser = null; port = null; hostname = "127.0.0.1"; apiClient = null; autoConnectAttempted = false
        toolWindowPanel?.removeAll()
        showCard(CARD_EMPTY)
    }

    private fun terminateProcess() {
        try {
            val process = terminalWidget?.processTtyConnector?.process
            if (process?.isAlive == true) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        } catch (_: Exception) {}
    }

    private fun showReconnectOrNewDialog(running: Boolean) {
        val msg = if (running) "Session active. Restore UI?" else "Server dead. Restart?"
        ApplicationManager.getApplication().invokeLater {
            val res = Messages.showYesNoCancelDialog(project, msg, "OpenCode", if (running) "Restore" else "Restart", "New", "Cancel", Messages.getQuestionIcon())
            if (res == Messages.YES) if (running) restoreUiForMode() else restartServer(lastMode)
            else if (res == Messages.NO) { disconnectAndReset(); showConnectionDialog() }
        }
    }

    private fun showConnectionDialog() {
        AppExecutorUtil.getAppExecutorService().submit {
            val suggested = PortFinder.findAvailablePort()
            ApplicationManager.getApplication().invokeLater {
                OpenCodeConnectDialog.show(project, suggested)?.let { 
                    processConnectionChoice(it.hostname, it.port, it.password, it.useWebInterface, it.customBasePath) 
                }
            }
        }
    }

    private fun processConnectionChoice(h: String, p: Int, pwd: String?, web: Boolean, customBasePath: String? = null) {
        val safeH = h.trim().ifBlank { "127.0.0.1" }
        val local = safeH in listOf("0.0.0.0", "127.0.0.1", "localhost")
        
        AppExecutorUtil.getAppExecutorService().submit {
            val a = if (!pwd.isNullOrBlank()) ProcessAuthDetector.ServerAuth("opencode", pwd) else if (local) ProcessAuthDetector.detectAuthForPort(p) else ProcessAuthDetector.ServerAuth("opencode", null)
            val running = if (local) PortFinder.isOpenCodeRunningOnPort(p, safeH, a.username, a.password) else true
            val occupied = if (local && !running) !PortFinder.isPortAvailable(p) else false

            ApplicationManager.getApplication().invokeLater {
                if (running) {
                    lastMode = if (!local) ConnectionMode.REMOTE else if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL
                    // For remote (!local), strict headless unless web mode.
                    // For local, if running, we also just connect (headless or web), we do NOT spawn a new terminal.
                    connectToExistingServer(safeH, p, a, web, web, customBasePath)
                } else if (occupied) {
                    val choice = Messages.showYesNoDialog(
                        project,
                        "Port $p is in use but did not pass the health check.\nIt might be an older OpenCode version or a different service.\n\nTry to connect anyway?",
                        "Connection Warning",
                        "Connect Anyway",
                        "Cancel",
                        Messages.getWarningIcon()
                    )
                    if (choice == Messages.YES) {
                        lastMode = if (!local) ConnectionMode.REMOTE else if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL
                        connectToExistingServer(safeH, p, a, web, web, customBasePath)
                    } else {
                        showConnectionDialog()
                    }
                } else {
                    // Not running and not occupied -> Start new server
                    if (!local) {
                         // Safety net: If remote host is not running, we cannot "start" it locally.
                         Messages.showErrorDialog(project, "Cannot connect to remote server $safeH:$p (Not reachable).", "Connection Failed")
                         showConnectionDialog()
                    } else {
                        lastMode = if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL
                        if (web) createWebTerminal(safeH, p, a.password, customBasePath) else createLocalTerminal(safeH, p, a.password, customBasePath)
                    }
                }
            }
        }
    }

    private fun connectToExistingServer(h: String, p: Int, a: ProcessAuthDetector.ServerAuth, ui: Boolean, web: Boolean, customBasePath: String? = null) {
        hostname = h; port = p; username = a.username; password = a.password
        if (ui) { if (web) createWebUI(h, p) else createTerminalUIInternal(h, p, a.password, false, null, customBasePath) }
        else { ApplicationManager.getApplication().invokeLater { Messages.showInfoMessage(project, "Connected to $h:$p", "OpenCode") } }
        initializeApiClient(h, p); startConnectionManager()
    }

    private fun createWebUI(h: String, p: Int) {
        webBrowser = WebModeSupport.createWebBrowser(project, h, p, password)
        if (webBrowser != null) {
            showContentInToolWindow()
            toolWindow?.show()
        }
    }

    private fun createWebTerminal(h: String, p: Int, pwd: String?, customBasePath: String? = null) {
        AppExecutorUtil.getAppExecutorService().submit {
            val bin = detectOpenCodeBinary()
            if (bin == null) {
                showCliNotFoundError()
                return@submit
            }
            ApplicationManager.getApplication().invokeLater {
                hostname = h; port = p; lastMode = ConnectionMode.WEB
                createTerminalUIInternal(h, p, pwd, true, bin, customBasePath)
            }
            // Increase timeout to 30s for all platforms
            if (!PortFinder.waitForPort(p, h, timeoutMs = 30000, requireHealth = true)) {
                logger.warn("[OpenCode] Initial waitForPort (Web) timed out for $h:$p, starting ConnectionManager anyway")
            }
            
            // Show the web UI even if timeout hasn't completed yet
            ApplicationManager.getApplication().invokeLater { createWebUI(h, p) }
            initializeApiClient(h, p)
            startConnectionManager()
        }
    }

    private fun createLocalTerminal(h: String, p: Int, pwd: String?, customBasePath: String? = null) {
        val safeH = h.trim().ifBlank { "127.0.0.1" }
        if (safeH !in listOf("0.0.0.0", "127.0.0.1", "localhost")) {
            // Guard: Never attempt to spawn local terminal for remote host
            connectToExistingServer(safeH, p, ProcessAuthDetector.ServerAuth("opencode", pwd), false, false, customBasePath)
            return
        }
        AppExecutorUtil.getAppExecutorService().submit {
            val bin = detectOpenCodeBinary()
            if (bin == null) {
                showCliNotFoundError()
                return@submit
            }
            ApplicationManager.getApplication().invokeLater {
                hostname = h; port = p; lastMode = ConnectionMode.TERMINAL
                createTerminalUIInternal(h, p, pwd, true, bin, customBasePath)
            }
            // Increase timeout to 30s for all platforms (Windows startup can be slow)
            if (!PortFinder.waitForPort(p, h, timeoutMs = 30000)) {
                logger.warn("[OpenCode] Initial waitForPort timed out for $h:$p, starting ConnectionManager anyway")
            }
            
            initializeApiClient(h, p)
            startConnectionManager()
        }
    }

    private fun createTerminalUIInternal(h: String, p: Int, pwd: String?, cont: Boolean = true, command: String? = null, customBasePath: String? = null) {
        logger.warn("[OpenCode] createTerminalUIInternal called h=$h p=$p")
        val wd = customBasePath ?: project.basePath
        val w = TerminalView.getInstance(project).createLocalShellWidget(wd, "OpenCode")
        try {
            val panel = w.getTerminalPanel()
            logger.warn("[OpenCode] Panel class=${panel.javaClass.name}")

            // JediTerm's TerminalPanel has two MouseWheelListeners:
            //   1. init() → scrolls via moveScrollBar(getUnitsToScroll()) on the BoundedRangeModel
            //   2. addTerminalMouseListener() → sends ↑↓ arrow keys to TTY when in alternate buffer
            //
            // Problem: In alternate buffer mode (TUI), the BoundedRangeModel has max-extent ≈ 0,
            // so even the init() scroll listener does nothing — the model has no scroll range.
            // The ONLY effective handler in TUI mode is #2 (arrow key forwarding), which scrolls
            // the TUI's input history instead of the terminal output.
            //
            // Solution: Remove BOTH listeners. The init() scroll is ineffective in TUI mode anyway.
            // The arrow-key forwarding is the actual problem — removing it stops the TUI interference.
            // We DO NOT add replacement scrolling because JediTerm's model doesn't support scrolling
            // in alternate buffer mode. The event is consumed to prevent propagation to parents.
            val original = panel.mouseWheelListeners.toList()
            logger.warn("[OpenCode] Removing ${original.size} mouse wheel listeners")
            original.forEach { panel.removeMouseWheelListener(it) }

            // Log BoundedRangeModel state for debugging
            val model = panel.verticalScrollModel
            logger.warn("[OpenCode] Model: min=${model.minimum} max=${model.maximum} extent=${model.extent} value=${model.value}")
            
            // Replace wheel scrolling with PageUp/PageDown key forwarding.
            // In TUI (alternate buffer) mode, JediTerm's BoundedRangeModel has max-extent ≈ 0
            // so native scroll is impossible. The original arrow-key forwarding (listener #2)
            // scrolled the TUI's input history, not output content.
            //
            // Converting wheel → PageUp/PageDown keys lets the TUI scroll output content
            // instead (most TUIs map PageUp/PageDown to content scrolling, ArrowUp/Down to
            // input history navigation).
            panel.addMouseWheelListener { e ->
                if (!e.isConsumed) {
                    val rotation = e.unitsToScroll
                    if (rotation != 0) {
                        val keyCode = if (rotation < 0) KeyEvent.VK_PAGE_UP else KeyEvent.VK_PAGE_DOWN
                        val keyEvent = KeyEvent(
                            panel, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                            keyCode, KeyEvent.CHAR_UNDEFINED
                        )
                        panel.dispatchEvent(keyEvent)
                        logger.warn("[OpenCode] Wheel → ${if (rotation < 0) "PageUp" else "PageDown"} sent to TUI")
                    }
                    e.consume()
                }
            }
            logger.warn("[OpenCode] Wheel intercept installed — PageUp/PageDown forwarding")
        } catch (e: Exception) {
            logger.warn("[OpenCode] Failed to set up terminal wheel scrolling: ${e.message}")
        }
        OpenCodeTerminalLinkFilter.install(project, w)
        
        // Store the widget reference
        terminalWidget = w
        
        // Always embed the widget in the ToolWindow panel directly.
        // DO NOT rely on onToolWindowOpened() to do this — if the ToolWindow is
        // already visible when this method runs, toolWindow.show() is a no-op
        // and the event is NOT fired, leaving the widget orphaned.
        showTerminalInToolWindow(w)
        
        // Show the toolwindow (no-op if already visible)
        toolWindow?.show()
        
        // After showing the toolwindow, schedule focus on the terminal widget
        // (the focus request in showTerminalInToolWindow happens before show, so it's ignored)
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && terminalWidget != null) {
                terminalWidget!!.preferredFocusableComponent?.requestFocusInWindow()
                logger.warn("[OpenCode] Focus requested on terminal widget")
            }
        }
        
        // Schedule command execution with a delay to let the shell (PowerShell) initialize.
        // On Windows, the newly created terminal's shell takes time to start up;
        // sending the command too early (within milliseconds) can result in the shell
        // not processing it correctly.
        //
        // IMPORTANT: Use the full binary path from detectOpenCodeBinary() if available,
        // rather than just "opencode" from getOpenCodeBinary(). The terminal's shell may
        // not have opencode in PATH even if the IDE does.
        val cmd = _cachedBinary ?: command ?: getOpenCodeBinary()
        val fullCommand = buildOpenCodeCommand(cmd, h, p, pwd)
        logger.warn("[OpenCode] Will execute command in 2s: $fullCommand")
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    logger.warn("[OpenCode] Executing command via executeCommand: $fullCommand")
                    try {
                        w.executeCommand(fullCommand)
                    } catch (e: Exception) {
                        logger.warn("[OpenCode] executeCommand failed: ${e.message}", e)
                    }
                }
            }
        }, 2000, TimeUnit.MILLISECONDS)
    }



    private fun buildOpenCodeCommand(command: String, h: String, p: Int, pwd: String?): String {
        // Quote command if it contains spaces (e.g. absolute path on Windows)
        val cmdSafe = if (command.contains(" ")) "\"$command\"" else command
        // NOTE: In opencode v1.15+, --hostname, --port, and --continue are NOT valid
        // flags for the base `opencode` command. They only work on subcommands
        // (web, serve, continue).
        // Running `opencode` without flags starts the TUI on default port 4096.
        // The API client connects to 127.0.0.1:4096.
        val base = cmdSafe
        if (pwd.isNullOrBlank()) return base
        return if (isWindows()) "cmd /c \"set \"OPENCODE_SERVER_PASSWORD=${pwd.replace("\"", "\\\"")}\" && $base\"" else "OPENCODE_SERVER_PASSWORD='${pwd.replace("'", "'\\''")}' $base"
    }

    @Volatile private var _cachedBinary: String? = null

    /** Resolve opencode binary path (safe for EDT - no process execution).
     *  Checks common installation dirs; returns full path or "opencode" as last resort. */
    private fun getOpenCodeBinary(): String {
        _cachedBinary?.let { return it }

        val home = System.getProperty("user.home")
        val appData = System.getenv("APPDATA") // e.g. C:\Users\Lenovo\AppData\Roaming
        val localAppData = System.getenv("LOCALAPPDATA")

        val candidates = if (isWindows()) {
            listOfNotNull(
                java.io.File(home, ".opencode/bin/opencode.exe"),
                java.io.File("C:\\Program Files\\opencode\\opencode.exe"),
                localAppData?.let { java.io.File(it, "opencode\\opencode.exe") },
                // npm global install: npm puts a .cmd wrapper at %APPDATA%\npm\opencode
                appData?.let { java.io.File(it, "npm\\opencode") },
                appData?.let { java.io.File(it, "npm\\opencode.cmd") },
            )
        } else {
            listOf(
                java.io.File(home, ".opencode/bin/opencode"),
                java.io.File("/opt/homebrew/bin/opencode"),
                java.io.File("/usr/local/bin/opencode"),
                java.io.File("/home/linuxbrew/.linuxbrew/bin/opencode"),
                java.io.File(home, ".linuxbrew/bin/opencode"),
                java.io.File("/usr/bin/opencode"),
                java.io.File("/snap/bin/opencode"),
            )
        }
        
        for (candidate in candidates) {
            if (candidate.exists() && candidate.canExecute()) {
                val path = candidate.absolutePath
                logger.info("[OpenCode] Resolved CLI to: $path")
                _cachedBinary = path
                return path
            }
        }

        // NOTE: This method is called from EDT. Do NOT run processes here.
        // `where`/`which` discovery is done in detectOpenCodeBinary() (background thread).

        return "opencode"
    }

    /** Detect OpenCode binary path (Background thread safe) */
    private fun detectOpenCodeBinary(): String? {
        val home = System.getProperty("user.home")
        val appData = System.getenv("APPDATA")
        val localAppData = System.getenv("LOCALAPPDATA")
        
        // Check common installation paths (ordered by priority)
        val candidates = if (isWindows()) {
            listOfNotNull(
                java.io.File(home, ".opencode/bin/opencode.exe"),
                java.io.File("C:\\Program Files\\opencode\\opencode.exe"),
                java.io.File("C:\\Program Files (x86)\\opencode\\opencode.exe"),
                localAppData?.let { java.io.File(it, "opencode\\opencode.exe") },
                // npm global install: npm puts a .cmd wrapper at %APPDATA%\npm\opencode
                appData?.let { java.io.File(it, "npm\\opencode") },
                appData?.let { java.io.File(it, "npm\\opencode.cmd") },
            )
        } else {
            listOf(
                java.io.File(home, ".opencode/bin/opencode"),           // npm global install
                java.io.File("/opt/homebrew/bin/opencode"),             // macOS ARM Homebrew
                java.io.File("/usr/local/bin/opencode"),                // macOS Intel Homebrew / Linux standard
                java.io.File("/home/linuxbrew/.linuxbrew/bin/opencode"),// Linux Homebrew
                java.io.File(home, ".linuxbrew/bin/opencode"),          // Linux Homebrew (user install)
                java.io.File("/usr/bin/opencode"),                      // System package manager
                java.io.File("/snap/bin/opencode"),                     // Snap install
            )
        }
        
        for (candidate in candidates) {
            if (candidate.exists() && candidate.canExecute()) {
                logger.info("[OpenCode] Detected CLI at: ${candidate.absolutePath}")
                _cachedBinary = candidate.absolutePath
                return candidate.absolutePath
            }
        }

        // Fallback: Check PATH (may fail in sandboxed environments like Snap)
        if (checkOpenCodeCliAvailable()) {
            logger.info("[OpenCode] CLI detected in PATH")
            // Try to get the full path via `where` / `which` (background-thread safe)
            try {
                val cmd = if (isWindows()) listOf("cmd", "/c", "where", "opencode")
                          else listOf("which", "opencode")
                val result = CapturingProcessHandler(GeneralCommandLine(cmd)).runProcess(5000)
                if (result.exitCode == 0) {
                    val lines = result.stdout.trim().lines().filter { it.isNotBlank() }
                    val resolvedPath = if (isWindows()) {
                        // On Windows, `where` returns extensionless files first (e.g. NVM's
                        // nodejs\opencode without .cmd). These are Node.js shebang scripts that
                        // Windows/PowerShell cannot execute directly — they cause a blank CMD
                        // console to open as Windows's "how to open" fallback.
                        // Prefer .cmd, .exe, .bat files which Windows knows how to run.
                        val preferred = lines.firstOrNull { line ->
                            line.endsWith(".cmd", ignoreCase = true) ||
                            line.endsWith(".exe", ignoreCase = true) ||
                            line.endsWith(".bat", ignoreCase = true)
                        }
                        if (preferred != null) {
                            logger.info("[OpenCode] Resolved CLI path (preferred Windows extension): $preferred")
                            preferred
                        } else {
                            // No .cmd/.exe/.bat found — try .js or fall back
                            val jsOrNone = lines.firstOrNull { line ->
                                line.endsWith(".js", ignoreCase = true)
                            } ?: lines.firstOrNull()
                            if (jsOrNone != null && !jsOrNone.endsWith(".js", ignoreCase = true) &&
                                !jsOrNone.any { c -> c == '.' && c != ':' && c != '\\' }) {
                                // Extensionless file — can't execute on Windows. Use just "opencode".
                                logger.warn("[OpenCode] `where` returned extensionless path: $jsOrNone. Using plain 'opencode' instead.")
                                "opencode"
                            } else {
                                jsOrNone
                            }
                        }
                    } else {
                        lines.firstOrNull()
                    }
                    if (!resolvedPath.isNullOrBlank()) {
                        _cachedBinary = resolvedPath
                        return resolvedPath
                    }
                }
            } catch (_: Exception) { }
            // Fall back to plain name if we can't resolve the full path
            _cachedBinary = "opencode"
            return "opencode"
        }

        return null
    }

    /** Check CLI availability (safe for background thread) */
    private fun checkOpenCodeCliAvailable(): Boolean {
        val cmds = if (isWindows()) {
            listOf(
                listOf("cmd", "/c", "where", "opencode"),
                listOf("powershell", "-Command", "Get-Command opencode")
            )
        } else {
            listOf(
                listOf("which", "opencode"),
                listOf("sh", "-lc", "command -v opencode")
            )
        }
        return cmds.any { try { CapturingProcessHandler(GeneralCommandLine(it)).runProcess(5000).exitCode == 0 } catch (_: Exception) { false } }
    }
    
    /** Show CLI not found error (must call on any thread, will dispatch to EDT) */
    private fun showCliNotFoundError() {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, "OpenCode CLI not found.", "Error")
        }
    }

    private fun isWindows() = System.getProperty("os.name", "").lowercase().contains("windows")

    /** Public API — disconnect and restart the current session */
    fun reconnect() {
        val m = lastMode
        val h = hostname; val p = port ?: return; val pwd = password
        disconnectAndReset()
        hostname = h; port = p; password = pwd; lastMode = m
        when (m) {
            ConnectionMode.WEB -> createWebTerminal(h, p, pwd)
            ConnectionMode.REMOTE -> connectToExistingServer(h, p, ProcessAuthDetector.ServerAuth("opencode", pwd), false, false)
            else -> createLocalTerminal(h, p, pwd)
        }
    }

    private fun restartServer(m: ConnectionMode) { 
        val h = hostname; val p = port ?: return; val pwd = password; disconnectAndReset(); hostname = h; port = p; password = pwd; lastMode = m; 
        if (m == ConnectionMode.WEB) createWebTerminal(h, p, pwd) 
        else if (m == ConnectionMode.REMOTE) connectToExistingServer(h, p, ProcessAuthDetector.ServerAuth("opencode", pwd), false, false)
        else createLocalTerminal(h, p, pwd) 
    }
    private fun focusTerminalUI() {
        toolWindow?.show()
        showContentInToolWindow()
    }
    private fun restoreUiForMode() { 
        when (lastMode) { 
            ConnectionMode.TERMINAL -> ensureTerminalUi()
            ConnectionMode.WEB -> ensureWebUi()
            ConnectionMode.REMOTE -> restoreRemoteConnection()
            else -> showConnectionDialog() 
        } 
    }
    private fun ensureTerminalUi() {
        if (terminalWidget != null) {
            focusTerminalUI()
        } else {
            // If we don't have a cached binary yet, run detection on background thread first,
            // then create terminal UI on EDT with the detected path.
            if (_cachedBinary == null) {
                AppExecutorUtil.getAppExecutorService().submit {
                    detectOpenCodeBinary()
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            doCreateTerminalUi()
                        }
                    }
                }
            } else {
                doCreateTerminalUi()
            }
        }
    }

    /** Create the terminal widget and run opencode. Called on EDT. */
    private fun doCreateTerminalUi() {
        try {
            createTerminalUIInternal(hostname, port ?: return, password, false)
        } catch (e: Exception) {
            logger.warn("[OpenCode] Failed to create terminal UI", e)
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(project, "Failed to create terminal: ${e.message}", "OpenCode")
            }
        }
    }
    private fun ensureWebUi() {
        if (webBrowser != null) {
            focusTerminalUI()
        } else {
            createWebUI(hostname, port ?: return)
        }
    }
    private fun restoreRemoteConnection() {
        // For remote connections, verify connection is alive and show status
        val p = port ?: return
        val h = hostname
        AppExecutorUtil.getAppExecutorService().submit {
            val running = PortFinder.isOpenCodeRunningOnPort(p, h, username, password)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (running) {
                    if (!isConnected.get()) {
                        // Reconnect SSE if not connected
                        initializeApiClient(h, p)
                        startConnectionManager()
                    }
                    Messages.showInfoMessage(project, "Connected to $h:$p", "OpenCode")
                } else {
                    Messages.showWarningDialog(project, "Remote server $h:$p is not reachable.", "OpenCode")
                }
            }
        }
    }
    private fun showHeadlessStatusDialog() { ApplicationManager.getApplication().invokeLater { if (Messages.showYesNoDialog(project, "Connected to $hostname:$port (Headless). Disconnect?", "OpenCode", "Disconnect", "Keep", Messages.getInformationIcon()) == Messages.YES) disconnectAndReset() } }
    
    private fun schedulePasteAttempt(t: String, l: Int, d: Long) { 
        if (l <= 0 || project.isDisposed) {
            if (l <= 0 && !project.isDisposed) {
                logger.warn("[Paste] All retries exhausted for: $t")
            }
            return
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ 
            ApplicationManager.getApplication().invokeLater { 
                if (!project.isDisposed) {
                    if (!pasteToTerminal(t)) {
                        schedulePasteAttempt(t, l - 1, d)
                    }
                }
            } 
        }, d, TimeUnit.MILLISECONDS) 
    }

    private fun extractPartMessageInfo(p: JsonElement): PartMessageInfo? { if (!p.isJsonObject) return null; val o = p.asJsonObject; val mId = o.get("messageID")?.asString; val sId = o.get("sessionID")?.asString; return if (mId != null && sId != null) PartMessageInfo(sId, mId) else null }
    private data class PartMessageInfo(val sessionId: String, val messageId: String)
}
