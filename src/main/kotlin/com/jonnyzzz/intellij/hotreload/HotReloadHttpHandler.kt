package com.jonnyzzz.intellij.hotreload

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.addCommonHeaders
import org.jetbrains.io.send
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HTTP REST endpoint handler for plugin hot reload functionality.
 * Endpoint: /api/plugin-hot-reload
 *
 * GET: Returns usage instructions (no authentication required)
 * POST: Accepts plugin zip file and performs hot reload (requires Bearer token)
 *       Returns streaming text/plain response with progress updates
 *
 * Authentication:
 * POST requests require an Authorization header with a Bearer token that matches
 * the token in the marker file. The token is generated when the IDE starts.
 */
class HotReloadHttpHandler : HttpRequestHandler() {
    private val prefix = "api/plugin-hot-reload"
    private val log = thisLogger()

    override fun isSupported(request: FullHttpRequest): Boolean {
        if (!checkPrefix(request.uri(), prefix)) {
            return false
        }
        val method = request.method()
        return method == HttpMethod.GET || method == HttpMethod.POST
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): Boolean {
        return when (request.method()) {
            HttpMethod.GET -> handleGet(request, context)
            HttpMethod.POST -> handlePost(request, context)
            else -> false
        }
    }

    private fun handleGet(request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        val pid = ProcessHandle.current().pid()
        val instructions = """
            {
              "status": "ok",
              "pid": $pid,
              "usage": {
                "GET": "Returns this information",
                "POST": "Upload a plugin .zip file to hot-reload it (requires Authorization header)"
              },
              "authentication": "POST requires 'Authorization: Bearer <token>' header. Token is in the marker file.",
              "response": "POST returns streaming text/plain with progress updates, one message per line",
              "example": "curl -X POST -H 'Authorization: Bearer <token>' --data-binary @plugin.zip http://localhost:<port>/api/plugin-hot-reload"
            }
        """.trimIndent()

        sendJsonResponse(context, request, HttpResponseStatus.OK, instructions)
        return true
    }

    private fun handlePost(request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        // Validate authentication
        val authHeader = request.headers().get(HttpHeaderNames.AUTHORIZATION)
        val markerService = service<HotReloadMarkerService>()
        val expectedToken = "Bearer ${markerService.authToken}"

        if (authHeader != expectedToken) {
            log.warn("Unauthorized hot-reload attempt. Expected token hash: ${markerService.authToken.hashCode()}, got: ${authHeader?.hashCode()}")
            sendJsonResponse(
                context, request, HttpResponseStatus.UNAUTHORIZED,
                """{"error": "Unauthorized. Provide valid 'Authorization: Bearer <token>' header."}"""
            )
            return true
        }

        val content = request.content()
        if (!content.isReadable || content.readableBytes() == 0) {
            sendJsonResponse(
                context, request, HttpResponseStatus.BAD_REQUEST,
                """{"error": "No file content provided"}"""
            )
            return true
        }

        // Read the zip content from the request body
        val bytes = ByteArray(content.readableBytes())
        content.readBytes(bytes)

        log.info("Received plugin zip file, size: ${bytes.size} bytes")

        // Start streaming response
        val channel = context.channel()
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        response.addCommonHeaders()

        channel.write(response)

        // Helper to write a line and flush
        fun writeLine(line: String) {
            val data = Unpooled.copiedBuffer("$line\n", Charsets.UTF_8)
            channel.writeAndFlush(DefaultHttpContent(data))
        }

        // Create progress reporter that streams to the HTTP response
        val progressReporter = object : PluginHotReloadService.ProgressReporter {
            override fun report(message: String) {
                log.info("Progress: $message")
                writeLine("INFO: $message")
            }

            override fun reportError(message: String) {
                log.warn("Error: $message")
                writeLine("ERROR: $message")
            }
        }

        // Process the plugin reload on EDT and wait for completion
        val latch = CountDownLatch(1)
        var result: PluginHotReloadService.ReloadResult? = null
        var notification: HotReloadNotifications.ProgressNotification? = null

        ApplicationManager.getApplication().invokeLater({
            try {
                // Show progress notification
                notification = HotReloadNotifications.showProgress("plugin")

                val reloadService = service<PluginHotReloadService>()
                result = reloadService.reloadPlugin(bytes, progressReporter)

                // Update notification based on result
                val r = result!!
                if (r.success) {
                    notification?.success()
                    HotReloadNotifications.showSuccess(r.pluginName ?: "Unknown")
                } else {
                    notification?.error(r.message)
                }
            } catch (e: Exception) {
                log.error("Plugin hot reload failed", e)
                progressReporter.reportError("Unexpected error: ${e.message}")
                notification?.error(e.message ?: "Unknown error")
                result = PluginHotReloadService.ReloadResult(false, "Unexpected error: ${e.message}")
            } finally {
                latch.countDown()
            }
        }, ModalityState.nonModal())

        // Wait for completion (with timeout)
        val completed = latch.await(5, TimeUnit.MINUTES)

        if (!completed) {
            writeLine("ERROR: Operation timed out")
            writeLine("RESULT: TIMEOUT")
        } else {
            val r = result
            if (r != null) {
                writeLine("")
                if (r.success) {
                    writeLine("RESULT: SUCCESS")
                    writeLine("PLUGIN: ${r.pluginName ?: r.pluginId ?: "Unknown"}")
                } else {
                    writeLine("RESULT: ${if (r.restartRequired) "RESTART_REQUIRED" else "FAILED"}")
                    writeLine("MESSAGE: ${r.message}")
                    if (r.pluginName != null) {
                        writeLine("PLUGIN: ${r.pluginName}")
                    }
                }
            }
        }

        // End chunked response
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
            .addListener(ChannelFutureListener.CLOSE)

        return true
    }

    private fun sendJsonResponse(
        context: ChannelHandlerContext,
        request: FullHttpRequest,
        status: HttpResponseStatus,
        json: String
    ) {
        val content = Unpooled.copiedBuffer(json, Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
        response.addCommonHeaders()
        response.send(context.channel(), request)
    }
}
