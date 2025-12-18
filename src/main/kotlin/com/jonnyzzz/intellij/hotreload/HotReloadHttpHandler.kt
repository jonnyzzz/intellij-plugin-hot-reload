package com.jonnyzzz.intellij.hotreload

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.addCommonHeaders
import org.jetbrains.io.send

/**
 * HTTP REST endpoint handler for plugin hot reload functionality.
 * Endpoint: /api/plugin-hot-reload
 *
 * GET: Returns usage instructions (no authentication required)
 * POST: Accepts plugin zip file and performs hot reload (requires Bearer token)
 *
 * Authentication:
 * POST requests require an Authorization header with a Bearer token that matches
 * the token in the marker file. The token is generated when the IDE starts.
 */
class HotReloadHttpHandler : HttpRequestHandler() {
    companion object {
        private const val PREFIX = "api/plugin-hot-reload"
        private val LOG = Logger.getInstance(HotReloadHttpHandler::class.java)
    }

    override fun isSupported(request: FullHttpRequest): Boolean {
        if (!checkPrefix(request.uri(), PREFIX)) {
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
            LOG.warn("Unauthorized hot-reload attempt. Expected token hash: ${markerService.authToken.hashCode()}, got: ${authHeader?.hashCode()}")
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

        LOG.info("Received plugin zip file, size: ${bytes.size} bytes")

        // Process the plugin reload on EDT
        ApplicationManager.getApplication().invokeLater {
            try {
                val reloadService = service<PluginHotReloadService>()
                val result = reloadService.reloadPlugin(bytes)

                // Note: Response already sent, this is for logging
                if (result.success) {
                    LOG.info("Plugin hot reload successful: ${result.message}")
                } else {
                    LOG.warn("Plugin hot reload failed: ${result.message}")
                }
            } catch (e: Exception) {
                LOG.error("Plugin hot reload failed", e)
            }
        }

        // Send immediate response that processing has started
        sendJsonResponse(
            context, request, HttpResponseStatus.ACCEPTED,
            """{"status": "processing", "message": "Plugin reload initiated"}"""
        )
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
