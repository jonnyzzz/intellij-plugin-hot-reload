package com.jonnyzzz.intellij.hotreload

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.response
import org.jetbrains.io.send

/**
 * HTTP REST endpoint handler for plugin hot reload functionality.
 * Endpoint: /api/plugin-hot-reload
 */
class HotReloadHttpHandler : HttpRequestHandler() {
    companion object {
        private const val PREFIX = "api/plugin-hot-reload"
    }

    override fun isSupported(request: FullHttpRequest): Boolean {
        return checkPrefix(request.uri(), PREFIX)
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): Boolean {
        val pid = ProcessHandle.current().pid()
        val responseJson = """{"status":"ok","pid":$pid}"""

        val response = response("application/json", null)
        response.headers().set("Content-Type", "application/json; charset=utf-8")

        val content = io.netty.buffer.Unpooled.copiedBuffer(responseJson, Charsets.UTF_8)
        val fullResponse = io.netty.handler.codec.http.DefaultFullHttpResponse(
            io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            content
        )
        fullResponse.headers().set("Content-Type", "application/json; charset=utf-8")
        fullResponse.send(context.channel(), request)

        return true
    }
}
