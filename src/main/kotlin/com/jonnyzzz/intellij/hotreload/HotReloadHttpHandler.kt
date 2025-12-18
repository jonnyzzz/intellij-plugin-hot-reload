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
 * HTTP REST endpoint for plugin hot reload.
 * Endpoint: /api/plugin-hot-reload
 *
 * GET: Returns usage instructions
 * POST: Accepts plugin zip, streams progress, ends with SUCCESS or FAILED
 */
class HotReloadHttpHandler : HttpRequestHandler() {
    private val prefix = "api/plugin-hot-reload"
    private val log = thisLogger()

    override fun isSupported(request: FullHttpRequest): Boolean {
        return checkPrefix(request.uri(), prefix) &&
            (request.method() == HttpMethod.GET || request.method() == HttpMethod.POST)
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): Boolean = when (request.method()) {
        HttpMethod.GET -> handleGet(request, context)
        HttpMethod.POST -> handlePost(request, context)
        else -> false
    }

    private fun handleGet(request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        val json = """{"status":"ok","pid":${ProcessHandle.current().pid()}}"""
        val content = Unpooled.copiedBuffer(json, Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json")
        response.addCommonHeaders()
        response.send(context.channel(), request)
        return true
    }

    private fun handlePost(request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        val markerService = service<HotReloadMarkerService>()
        val authHeader = request.headers().get(HttpHeaderNames.AUTHORIZATION)

        if (authHeader != "Bearer ${markerService.authToken}") {
            return sendError(context, request, HttpResponseStatus.UNAUTHORIZED, "Unauthorized")
        }

        val content = request.content()
        if (!content.isReadable || content.readableBytes() == 0) {
            return sendError(context, request, HttpResponseStatus.BAD_REQUEST, "No content")
        }

        val bytes = ByteArray(content.readableBytes())
        content.readBytes(bytes)
        log.info("Received plugin zip: ${bytes.size} bytes")

        // Start streaming response
        val channel = context.channel()
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        response.addCommonHeaders()
        channel.write(response)

        fun writeLine(line: String) {
            channel.writeAndFlush(DefaultHttpContent(Unpooled.copiedBuffer("$line\n", Charsets.UTF_8)))
        }

        val progressReporter = object : PluginHotReloadService.ProgressReporter {
            override fun report(message: String) {
                log.info(message)
                writeLine(message)
            }
            override fun reportError(message: String) {
                log.warn(message)
                writeLine("ERROR: $message")
            }
        }

        val latch = CountDownLatch(1)
        var result: PluginHotReloadService.ReloadResult? = null
        val notifications = HotReloadNotificationService.getInstance()

        ApplicationManager.getApplication().invokeLater({
            try {
                val reloadService = service<PluginHotReloadService>()
                result = reloadService.reloadPlugin(bytes, progressReporter)

                val r = result!!
                if (r.success) {
                    notifications.showSuccess(r.pluginId ?: "unknown", r.pluginName ?: "Unknown")
                } else {
                    notifications.showError(r.pluginId, r.pluginName, r.message)
                }
            } catch (e: Exception) {
                log.error("Hot reload failed", e)
                progressReporter.reportError(e.message ?: "Unknown error")
                notifications.showError(null, null, e.message ?: "Unknown error")
                result = PluginHotReloadService.ReloadResult(false, e.message ?: "Unknown error")
            } finally {
                latch.countDown()
            }
        }, ModalityState.nonModal())

        val completed = latch.await(5, TimeUnit.MINUTES)
        val r = result

        // Last line is always SUCCESS or FAILED
        when {
            !completed -> writeLine("FAILED")
            r?.success == true -> writeLine("SUCCESS")
            else -> writeLine("FAILED")
        }

        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
            .addListener(ChannelFutureListener.CLOSE)
        return true
    }

    private fun sendError(
        context: ChannelHandlerContext,
        request: FullHttpRequest,
        status: HttpResponseStatus,
        message: String
    ): Boolean {
        val json = """{"error":"$message"}"""
        val content = Unpooled.copiedBuffer(json, Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json")
        response.addCommonHeaders()
        response.send(context.channel(), request)
        return true
    }
}
