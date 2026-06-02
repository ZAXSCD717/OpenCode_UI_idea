package ai.opencode.ide.jetbrains.web

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.*
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.util.*
import javax.swing.SwingUtilities

import ai.opencode.ide.jetbrains.util.PathUtil
import java.net.URLEncoder

object WebModeSupport {

    private val logger = Logger.getInstance(WebModeSupport::class.java)

    fun isJcefSupported(): Boolean {
        return try {
            JBCefApp.isSupported()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Create a JBCefBrowser configured to connect to the OpenCode web interface.
     * The caller (OpenCodeService) is responsible for embedding the browser component
     * in the toolwindow panel and managing its lifecycle.
     */
    fun createWebBrowser(
        project: Project,
        host: String,
        port: Int,
        password: String? = null
    ): JBCefBrowser? {
        if (!isJcefSupported()) {
            Messages.showErrorDialog(
                project,
                "JCEF (Embedded Browser) is not supported in this IDE environment.",
                "Web Interface Error"
            )
            return null
        }

        // Sanitize host for browser connection (0.0.0.0 is not valid for browser navigation)
        val browserHost = if (host == "0.0.0.0") "127.0.0.1" else host

        // Build clean URL without credentials; auth is injected via header
        val directoryParam = project.basePath?.let { basePath ->
            val serverPath = PathUtil.toOpenCodeServerPath(basePath)
            URLEncoder.encode(serverPath, "UTF-8")
        }
        val url = if (directoryParam != null) {
            "http://$browserHost:$port/?directory=$directoryParam"
        } else {
            "http://$browserHost:$port/"
        }

        logger.info("Creating JBCefBrowser for $url")
        val browser = JBCefBrowser(url)

        // 1. Capture JS Console Logs
        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                browser: CefBrowser?,
                level: CefSettings.LogSeverity?,
                message: String?,
                source: String?,
                line: Int
            ): Boolean {
                logger.info("[JS-Console] [$level] $message ($source:$line)")
                return false
            }
        }, browser.cefBrowser)

        // 2. Inject Authentication via Header
        if (!password.isNullOrBlank()) {
            val authString = "opencode:$password"
            val encodedAuth = Base64.getEncoder().encodeToString(authString.toByteArray())
            val authHeader = "Basic $encodedAuth"

            browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun getResourceRequestHandler(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    isNavigation: Boolean,
                    isDownload: Boolean,
                    requestInitiator: String?,
                    disableDefaultHandling: BoolRef?
                ): CefResourceRequestHandler? {
                    return object : CefResourceRequestHandlerAdapter() {
                        override fun onBeforeResourceLoad(
                            browser: CefBrowser?,
                            frame: CefFrame?,
                            request: CefRequest?
                        ): Boolean {
                            if (request != null && request.url.startsWith(url)) {
                                val headers = HashMap<String, String>()
                                request.getHeaderMap(headers)
                                headers["Authorization"] = authHeader
                                request.setHeaderMap(headers)
                                logger.info("Injected Auth header for request: ${request.url}")
                            }
                            return false
                        }
                    }
                }
            }, browser.cefBrowser)
        }

        // 3. Add Load Handler to catch errors and inject polyfills
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    // Inject clipboard polyfill to prevent JS errors in JCEF
                    val polyfillScript = """
                        (function() {
                            if (!navigator.clipboard) {
                                navigator.clipboard = {};
                            }
                            if (!navigator.clipboard.writeText) {
                                navigator.clipboard.writeText = function(text) {
                                    console.log('[Polyfill] Clipboard writeText called (not supported in embedded browser)');
                                    return Promise.resolve();
                                };
                            }
                            if (!navigator.clipboard.readText) {
                                navigator.clipboard.readText = function() {
                                    console.log('[Polyfill] Clipboard readText called (not supported in embedded browser)');
                                    return Promise.resolve('');
                                };
                            }
                        })();
                    """.trimIndent()
                    browser?.executeJavaScript(polyfillScript, "polyfill://clipboard", 0)
                    logger.info("Injected clipboard polyfill")
                }
            }

            override fun onLoadError(
                browser: CefBrowser?,
                frame: CefFrame?,
                errorCode: CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?
            ) {
                if (frame?.isMain == true) {
                    logger.warn("Web Page Load Error: $errorCode - $errorText ($failedUrl)")
                    SwingUtilities.invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Failed to load OpenCode Web Interface.\n\nURL: $failedUrl\nError: $errorText ($errorCode)",
                            "Connection Error"
                        )
                    }
                }
            }
        }, browser.cefBrowser)

        return browser
    }
}
