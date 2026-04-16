package com.jonnyzzz.intellij.hotreload

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.concurrency.AppExecutorUtil
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.addCommonHeaders
import org.jetbrains.io.send
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
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
        HttpMethod.POST -> handlePost(urlDecoder, request, context)
        else -> false
    }

    private fun handleGet(request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        val readme = javaClass.getResourceAsStream("/hot-reload/README.md")
            ?.bufferedReader()?.readText()
            ?: "README.md not found in resources"

        val content = Unpooled.copiedBuffer(readme, Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/markdown; charset=utf-8")
        response.addCommonHeaders()
        response.send(context.channel(), request)
        return true
    }

    private fun handlePost(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        val markerService = service<HotReloadMarkerService>()
        val authHeader = request.headers().get(HttpHeaderNames.AUTHORIZATION)

        if (authHeader != "Bearer ${markerService.authToken}") {
            return sendError(context, request, HttpResponseStatus.UNAUTHORIZED, "Unauthorized")
        }

        // File-based transfer via ?local-disk-file=<path> query parameter.
        // Avoids IntelliJ's built-in server body size limit (ide.netty.max.frame.size.in.mb,
        // default 180 MB). The client passes the absolute path to a ZIP on the local filesystem;
        // the server reads it directly, bypassing the Netty body aggregator entirely.
        val localDiskFile = urlDecoder.parameters()["local-disk-file"]?.firstOrNull()
        if (localDiskFile != null) {
            val file = Path.of(localDiskFile)
            if (!Files.exists(file)) {
                return sendError(context, request, HttpResponseStatus.BAD_REQUEST, "File not found: $localDiskFile")
            }
            log.info("File-based plugin reload from: $localDiskFile (${Files.size(file)} bytes)")
            return executeReload(context) { reloadService, progress ->
                reloadService.reloadPluginFromZipFile(file, progress)
            }
        }

        // Body-based transfer (original behavior, works for plugins under 180 MB).
        // Note: IntelliJ's built-in Netty server limits request bodies to 180 MB
        // (ide.netty.max.frame.size.in.mb). Larger plugins must use ?local-disk-file=<path>.
        val content = request.content()
        if (!content.isReadable || content.readableBytes() == 0) {
            return sendError(context, request, HttpResponseStatus.BAD_REQUEST, "No content. Either send the zip as body or use ?local-disk-file=<path>")
        }

        val bytes = ByteArray(content.readableBytes())
        content.readBytes(bytes)
        log.info("Received plugin zip: ${bytes.size} bytes")

        return executeReload(context) { reloadService, progress ->
            reloadService.reloadPlugin(bytes, progress)
        }
    }

    private fun executeReload(
        context: ChannelHandlerContext,
        reload: (PluginHotReloadService, PluginHotReloadService.ProgressReporter) -> PluginHotReloadService.ReloadResult
    ): Boolean {
        // Start streaming response
        val channel = context.channel()
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
        HttpUtil.setTransferEncodingChunked(response, true)
        response.addCommonHeaders()
        channel.writeAndFlush(response)

        val resultWriter = StringWriter()

        fun writeLine(line: String) {
            log.info("Hot reload progress: $line")
            resultWriter.write(line + "\n")
        }

        val progressReporter = object : PluginHotReloadService.ProgressReporter {
            override fun report(message: String) {
                writeLine(message)
            }
            override fun reportError(message: String) {
                writeLine("ERROR: $message")
            }
        }

        val latch = CountDownLatch(1)
        var result: PluginHotReloadService.ReloadResult? = null
        val notifications = HotReloadNotificationService.getInstance()

        ApplicationManager.getApplication().invokeLater({
            try {
                val reloadService = service<PluginHotReloadService>()
                val r = reload(reloadService, progressReporter)
                result = r

                val pluginInfo = r.toPluginInfo()
                if (r.success) {
                    notifications.showSuccess(pluginInfo)
                } else {
                    notifications.showError(pluginInfo, r.message)
                }
            } catch (e: Exception) {
                log.warn("Hot reload failed", e)
                progressReporter.reportError(e.message ?: "Unknown error")
                notifications.showError(PluginInfo.Unknown, e.message ?: "Unknown error")
                result = PluginHotReloadService.ReloadResult(false, e.message ?: "Unknown error")
            } finally {
                //a dirty hack to make sure all background invocables are completed
                AppExecutorUtil.getAppScheduledExecutorService().schedule(Runnable {
                    ApplicationManager.getApplication().invokeLater(Runnable {
                        latch.countDown()
                    }, ModalityState.nonModal())
                }, 5, TimeUnit.SECONDS)
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

        channel.write(DefaultHttpContent(Unpooled.copiedBuffer(resultWriter.toString(), Charsets.UTF_8)))
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
