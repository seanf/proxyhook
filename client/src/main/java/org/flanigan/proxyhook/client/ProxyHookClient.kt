/*
 * Copyright 2017, Red Hat, Inc. and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.flanigan.proxyhook.client

import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue

import org.flanigan.proxyhook.common.AbstractProxyHook
import org.flanigan.proxyhook.common.MessageType
import io.vertx.core.AsyncResult
import io.vertx.core.MultiMap
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.impl.FrameType
import io.vertx.core.http.impl.ws.WebSocketFrameImpl
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory

import java.lang.System.getenv
import org.flanigan.proxyhook.common.Constants.MAX_FRAME_SIZE
import org.flanigan.proxyhook.common.Constants.PATH_WEBSOCKET
import org.flanigan.proxyhook.common.Constants.PROXYHOOK_PASSWORD
import org.flanigan.proxyhook.common.JsonUtil.jsonToMultiMap
import org.flanigan.proxyhook.common.JsonUtil.multiMapToJson
import org.flanigan.proxyhook.common.Keys.BUFFER
import org.flanigan.proxyhook.common.Keys.BUFFER_TEXT
import org.flanigan.proxyhook.common.Keys.HEADERS
import org.flanigan.proxyhook.common.Keys.PASSWORD
import org.flanigan.proxyhook.common.Keys.PING_ID
import org.flanigan.proxyhook.common.Keys.TYPE
import org.flanigan.proxyhook.common.MessageType.LOGIN
import org.flanigan.proxyhook.common.MessageType.PONG

/**
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
class ProxyHookClient : AbstractProxyHook() {

    companion object {
        private val APP_NAME = ProxyHookClient::class.java.name
        private val log = LoggerFactory.getLogger(ProxyHookClient::class.java)
        // Request header references:
        // https://docs.gitlab.com/ce/user/project/integrations/webhooks.html
        // https://gitlab.com/gitlab-org/gitlab-ce/blob/v9.1.2/app/models/hooks/web_hook.rb#L60
        // https://developer.github.com/webhooks/#delivery-headers
        // https://en.wikipedia.org/wiki/List_of_HTTP_header_fields#Request_fields
        private val HEADERS_TO_COPY = setOf(
                "Accept",
                "Accept-Charset",
                "Accept-Datetime",
                "Accept-Encoding",
                "Accept-Language",
                "Authorization",
                "Content-Length",
                "Content-MD5",
                "Content-Type",
                "Cookie",
                "Date",
                "Expect",
                "Forwarded",
                "From",
                "Front-End-Https",
                "Max-Forwards",
                "Pragma",
                "Referer", // sic
                "TE",
                "User-Agent",
                "Via",
                "Warning",
                "X-Client-Ip",
                "X-Correlation-ID",
                "X-Forwarded-For",
                "X-Forwarded-Host",
                "X-Forwarded-Proto",
                "X-Forwarded-Server",
                "X-Gitlab-Event",
                "X-Gitlab-Token",
                "X-GitHub-Delivery",
                "X-GitHub-Event",
                "X-HTTP-Method-Override",
                "X-Hub-Signature",
                "X-Request-Id")
                .map { it.toLowerCase() }

        // deliberately not included: Connection, Host, Origin, If-*, Cache-Control, Proxy-Authorization, Range, Upgrade
        private var processArgs: List<String>? = null

        @JvmStatic fun main(args: Array<String>) {
            processArgs = args.toList()
            val q = ArrayBlockingQueue<AsyncResult<String>>(1)
            Vertx.vertx().deployVerticle(ProxyHookClient(), { q.offer(it) })
            val deploymentResult = q.take()
            if (deploymentResult.failed()) throw deploymentResult.cause()
        }
    }

    // TODO use http://vertx.io/docs/vertx-core/java/#_vert_x_command_line_interface_api
    // not this mess.
    // Command line is of the pattern "vertx run [options] main-verticle"
    // so strip off everything up to the Verticle class name.
    private fun findArgs(): List<String> {
        processArgs?.let { return it }
        val processArgs = vertx.orCreateContext.processArgs()
        log.debug("processArgs: " + processArgs)
        val n = processArgs.indexOf(javaClass.name)
        val argsAfterClass = processArgs.subList(n + 1, processArgs.size)
        val result = argsAfterClass.filter { arg -> !arg.startsWith("-") }
        log.debug("args: " + result)
        return result
    }

    @Throws(Exception::class)
    override fun start() {
        // this /shouldn't/ be needed for deployment failures:
        // vertx.exceptionHandler(this::die);
        val args = findArgs()
        if (args.size < 2) {
            die<Any>("Usage: wss://proxyhook.example.com/$PATH_WEBSOCKET http://target1.example.com/webhook [http://target2.example.com/webhook ...]")
        }
        startClient(args[0], args.subList(1, args.size))
    }

    private fun startClient(webSocketUrl: String, webhookUrls: List<String>) {
        log.info("starting client for websocket: $webSocketUrl posting to webhook URLs: $webhookUrls")

        webhookUrls.forEach { this.checkURI(it) }

        val wsUri = parseUri(webSocketUrl)
        val webSocketRelativeUri = getRelativeUri(wsUri)
        val useSSL = getSSL(wsUri)
        val wsOptions = HttpClientOptions()
                // 60s timeout based on pings from every 50s (both directions)
                .setIdleTimeout(60)
                .setConnectTimeout(10_000)
                .setDefaultHost(wsUri.host)
                .setDefaultPort(getWebsocketPort(wsUri))
                .setMaxWebsocketFrameSize(MAX_FRAME_SIZE)
                .setSsl(useSSL)
        val wsClient = vertx.createHttpClient(wsOptions)
        val httpClient = vertx.createHttpClient()

        connect(webhookUrls, webSocketRelativeUri, wsClient, httpClient)
    }

    private fun connect(webhookUrls: List<String>,
                        webSocketRelativeUri: String, wsClient: HttpClient,
                        httpClient: HttpClient) {
        wsClient.websocket(webSocketRelativeUri, { webSocket ->
            var password: String? = getenv(PROXYHOOK_PASSWORD)
            if (password == null) password = ""
            log.info("trying to log in")
            val login = JsonObject()
            login.put(TYPE, LOGIN)
            login.put(PASSWORD, password)
            webSocket.writeTextMessage(login.encode())

            // tries to reconnect in case:
            // - server is restarted
            // - server is still starting
            // - connection breaks because of transient network error
            // TODO OR just die, so that (eg) systemd can restart the process

            val periodicTimer = vertx.setPeriodic(50_000) {
                // ping frame triggers pong frame (inside vert.x), closes websocket if no data received before idleTimeout in TCPSSLOptions):
                // TODO avoid importing from internal vertx package
                val frame = WebSocketFrameImpl(FrameType.PING, io.netty.buffer.Unpooled.copyLong(System.currentTimeMillis()))
                webSocket.writeFrame(frame)

                // this doesn't work with a simple idle timeout, because sending the PING is considered write activity
                //                JsonObject object = new JsonObject();
                //                object.put(TYPE, PING);
                //                object.put(PING_ID, String.valueOf(System.currentTimeMillis()));
                //                webSocket.writeTextMessage(object.encode());
            }
            webSocket.handler { buf: Buffer ->
                val msg = buf.toJsonObject()
                log.debug("payload: {0}", msg)

                val type = msg.getString(TYPE)
                val messageType = MessageType.valueOf(type)
                when (messageType) {
                    MessageType.SUCCESS -> log.info("logged in")
                    MessageType.FAILED -> {
                        webSocket.close()
                        wsClient.close()
                        die<Any>("login failed")
                    }
                    MessageType.WEBHOOK -> handleWebhook(webhookUrls, httpClient, msg)
                    MessageType.PING -> {
                        val pingId = msg.getString(PING_ID)
                        log.debug("received PING with id {}", pingId)
                        val pong = JsonObject()
                        pong.put(TYPE, PONG)
                        pong.put(PING_ID, pingId)
                        webSocket.writeTextMessage(pong.encode())
                    }
                    PONG -> {
                        val pongId = msg.getString(PING_ID)
                        // TODO check ping ID
                        log.debug("received PONG with id {}", pongId)
                    }
                    else -> {
                        // TODO this might happen if the server is newer than the client
                        // should we log a warning and keep going, to be more robust?
                        webSocket.close()
                        wsClient.close()
                        die<Any>("unexpected message type: " + type)
                    }
                }
            }
            webSocket.closeHandler {
                log.info("websocket closed")
                vertx.cancelTimer(periodicTimer)
                vertx.setTimer(300) {
                    connect(webhookUrls,
                            webSocketRelativeUri, wsClient, httpClient)
                }
            }
            webSocket.exceptionHandler { e ->
                log.error("websocket stream exception", e)
                vertx.cancelTimer(periodicTimer)
                vertx.setTimer(2000) {
                    connect(webhookUrls,
                            webSocketRelativeUri, wsClient, httpClient)
                }
            }
        }) { e ->
            log.error("websocket connection exception", e)
            vertx.setTimer(2000) {
                connect(webhookUrls,
                        webSocketRelativeUri, wsClient, httpClient)
            }
        }
    }

    private fun checkURI(uri: String) {
        val hostname = parseUri(uri).host
        try {
            // ignoring result:
            InetAddress.getByName(hostname)
        } catch (e: UnknownHostException) {
            die<Any>("Unable to resolve URI " + uri + ": " + e.message)
        }

    }

    private fun handleWebhook(webhookUrls: List<String>, client: HttpClient, msg: JsonObject) {
        log.info("Webhook received")

        // TODO use host, path and/or query from webSocket message?
        //                String path = msg.getString("path");
        //                String query = msg.getString("query");
        val headerPairs = msg.getJsonArray(HEADERS)
        val headers = jsonToMultiMap(headerPairs)

        for (header in EVENT_ID_HEADERS) {
            if (headers.contains(header)) {
                log.info("Webhook header {0}: {1}", header, headers.getAll(header))
            }
        }

        val bufferText = msg.getString(BUFFER_TEXT)
        val buffer = msg.getBinary(BUFFER)
        //                log.debug("buffer: "+ Arrays.toString(buffer));

        for (webhookUri in webhookUrls) {
            val request = client.postAbs(webhookUri) { response ->
                log.info("Webhook POSTed to URL: " + webhookUri)
                log.info("Webhook POST response status: " + response.statusCode() + " " + response.statusMessage())
                log.info("Webhook POST response headers: " + multiMapToJson(response.headers()))
            }
            //                request.putHeader("content-type", "text/plain")
            // some headers break things (eg Host), so we use a whitelist
            //            request.headers().addAll(headers);
            copyWebhookHeaders(headers, request.headers())
            if (bufferText != null) {
                request.end(Buffer.buffer(bufferText))
            } else {
                request.end(Buffer.buffer(buffer))
            }
        }
    }

    private fun copyWebhookHeaders(fromHeaders: MultiMap, toHeaders: MultiMap) {
        for (header in fromHeaders.names()) {
            if (HEADERS_TO_COPY.contains(header.toLowerCase())) {
                toHeaders.set(header, fromHeaders.getAll(header))
            }
        }
    }

    private fun getRelativeUri(uri: URI): String {
        return uri.rawPath + if (uri.query == null) "" else "?" + uri.query
    }

    private fun getSSL(wsUri: URI): Boolean {
        val protocol = wsUri.scheme
        when (protocol) {
            "ws" -> return false
            "wss" -> return true
            else -> return die("expected URI with ws: or wss: : " + wsUri)
        }
    }

    private fun getWebsocketPort(wsUri: URI): Int {
        val port = wsUri.port
        if (port == -1) {
            val protocol = wsUri.scheme
            when (protocol) {
                "ws" -> return 80
                "wss" -> return 443
                else -> return die("expected URI with ws: or wss: : " + wsUri)
            }
        }
        return port
    }

    private fun parseUri(uri: String): URI {
        try {
            return URI(uri)
        } catch (e: URISyntaxException) {
            return die("Invalid URI: " + uri)
        }

    }

}