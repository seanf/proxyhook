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
package org.zanata.proxyhook.server

import io.vertx.core.Future
import org.mindrot.jbcrypt.BCrypt
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.EventBus
import io.vertx.core.http.CaseInsensitiveHeaders
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.http.WebSocketBase
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.shareddata.AsyncMap
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.ErrorHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.experimental.launch
import org.zanata.proxyhook.common.Constants.EVENT_ID_HEADERS
import org.zanata.proxyhook.common.Constants.MAX_BODY_SIZE
import org.zanata.proxyhook.common.Constants.MAX_FRAME_SIZE
import org.zanata.proxyhook.common.Constants.PATH_WEBHOOK
import org.zanata.proxyhook.common.Constants.PATH_WEBSOCKET
import org.zanata.proxyhook.common.Constants.PROXYHOOK_PASSHASH
import org.zanata.proxyhook.common.Keys.BUFFER
import org.zanata.proxyhook.common.Keys.BUFFER_TEXT
import org.zanata.proxyhook.common.Keys.HEADERS
import org.zanata.proxyhook.common.Keys.HOST
import org.zanata.proxyhook.common.Keys.PASSWORD
import org.zanata.proxyhook.common.Keys.PATH
import org.zanata.proxyhook.common.Keys.PING_ID
import org.zanata.proxyhook.common.Keys.QUERY
import org.zanata.proxyhook.common.Keys.TYPE
import org.zanata.proxyhook.common.MessageType
import org.zanata.proxyhook.common.MessageType.FAILED
import org.zanata.proxyhook.common.MessageType.LOGIN
import org.zanata.proxyhook.common.MessageType.PING
import org.zanata.proxyhook.common.MessageType.PONG
import org.zanata.proxyhook.common.MessageType.SUCCESS
import org.zanata.proxyhook.common.MessageType.WEBHOOK
import org.zanata.proxyhook.common.StartupException
import org.zanata.proxyhook.common.multiMapToJson
import java.lang.System.getenv
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The server component of ProxyHook, implemented as a vert.x verticle.
 * @param port TCP port to listen on.
 * Use null for default (8080 unless system property http.port is set)
 * Use 0 for a random port.
 * @param prefix webapp prefix, eg "/proxyhook".
 * Use empty string for root deployment.
 * Use null to use environment variable PROXYHOOK_PREFIX.
 * @param actualPort optional Future which will receive the assigned port
 * when deployment is complete.
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
class ProxyHookServer(
        private val port: Int? = null,
        private val prefix: String = getenv("PROXYHOOK_PREFIX") ?: "",
        var actualPort: Future<Int>? = null) : CoroutineVerticle() {

    private val sharedData by lazy { vertx.sharedData() }
    private val eventBus: EventBus get() = vertx.eventBus()
    private val passhash: String? = getenv(PROXYHOOK_PASSHASH)
    // map of websockets which are connected directly to this verticle (not via clustering)
    private val localConnections = HashSet<String>()
    // map of websocket IDs to TRUE (used like a Set)
    private lateinit var connections: AsyncMap<String, Boolean>

    override suspend fun start() {
        // a map of websocket connections across the vert.x cluster
        connections = awaitResult {
            sharedData.getAsyncMap("connections", it)
        }

        if (passhash != null) {
            log.info("password is set")
        } else {
            log.warn("{0} is not set; authentication is disabled", PROXYHOOK_PASSHASH)
        }
        val listenHost = System.getProperty("http.address", "127.0.0.1")
        val listenPort: Int = port ?: Integer.getInteger("http.port", 8080)
        log.info("Starting webhook/websocket server on $listenHost:$listenPort")

        val options = HttpServerOptions().apply {
            // 60s timeout based on pings every 50s
            idleTimeout = 60
            maxWebsocketFrameSize = MAX_FRAME_SIZE
            host = listenHost
            port = listenPort
        }
        val server = vertx.createHttpServer(options)
        // a set of textHandlerIds for connected websockets

        vertx.setPeriodic(50_000) {
            // in a cluster, we should only ping websockets connected to this verticle directly
            localConnections.forEach(this::pingConnection)
        }

        val router = Router.router(vertx)
        router.exceptionHandler { t -> log.error("Unhandled exception", t) }
        router.route()
                .handler(BodyHandler.create().setBodyLimit(MAX_BODY_SIZE.toLong()))
//                .handler(LoggerHandler.create())
                .failureHandler(ErrorHandler.create())
        // we need to respond to GET / so that health checks will work:
        router.get("$prefix/").coroutineHandler { rootHandler(it) }
        // see https://github.com/vert-x3/vertx-health-check if we need more features
        router.get("$prefix/ready").coroutineHandler { readyHandler(it) }
        router.post("$prefix/$PATH_WEBHOOK").coroutineHandler { webhookHandler(it) }
        server.requestHandler { router.accept(it) }

        server.websocketHandler { ws: ServerWebSocket ->
            if (ws.path() != "$prefix/$PATH_WEBSOCKET") {
                log.warn("wrong path for websocket connection: {0}", ws.path())
                ws.reject()
                return@websocketHandler
            }
            handleListen(ws)
        }

        server.listen { res ->
            if (res.failed()) {
                actualPort?.fail(res.cause())
                throw StartupException(res.cause())
            } else {
                log.info("Started server on port ${server.actualPort()}")
                logEndPoints(server.actualPort())
                actualPort?.complete(server.actualPort())
            }
        }
    }

    private fun logEndPoints(actualPort: Int) {
        val hostname = System.getenv("OPENSHIFT_APP_DNS")
        if (hostname != null) {
            log.info("Running on OpenShift")
            // TODO handle proxyhookContext (or remove OpenShift support)
            log.info("Webhooks should be POSTed to https://{0}/webhook (secure) or http://{0}/webhook (insecure)", hostname)
            log.info("ProxyHook client should connect to wss://{0}:8433/listen (secure) or ws://{0}:8000/listen (insecure)", hostname)
        } else {
            val port = actualPort.toString() // we don't want commas for thousands
            log.info("Webhooks should be POSTed to http://{0}:{1}{2}/webhook (insecure)", localHostName, port, prefix)
            log.info("ProxyHook client should connect to ws://{0}:{1}{2}/listen (insecure)", localHostName, port, prefix)
        }
    }

    // The prefix "fetch" is because this is a bit expensive
    private suspend fun fetchConnectionCount(): Int {
        return awaitResult { connections.size(it) }
    }

    private suspend fun rootHandler(context: RoutingContext) {
        val count = fetchConnectionCount()
        context.response().setStatusCode(HTTP_OK).end(APP_NAME + " (" + describe(count) + ")")
    }

    private suspend fun readyHandler(context: RoutingContext) {
        val count = fetchConnectionCount()
        context.response()
                // if there are no connections, webhooks won't be delivered, thus
                // HTTP_SERVICE_UNAVAILABLE (allows web pingers to check if it's all working)
                .setStatusCode(if (count == 0) HTTP_SERVICE_UNAVAILABLE else HTTP_OK)
                .end(APP_NAME + " (" + describe(count) + ")")
    }

    private suspend fun webhookHandler(context: RoutingContext) {
        log.info("handling POST request")
        val req = context.request()
        val headers = req.headers()
        EVENT_ID_HEADERS
                .filter { headers.contains(it) }
                .forEach { log.info("{0}: {1}", it, headers.getAll(it)) }
        val listeners = awaitResult<Set<String>> { connections.keys(it) }
        log.info("handling POST for {0} listeners", listeners.size)
        val statusCode: Int
        if (!listeners.isEmpty()) {
            val body = context.body
            val msgString = encodeWebhook(req, body)
            for (connection in listeners) {
                eventBus.send(connection, msgString)
            }
            log.info("Webhook " + req.path() + " received " + body.length() + " bytes. Forwarded to " + describe(listeners.size) + ".")
            statusCode = HTTP_OK
        } else {
            // nothing to do
            log.warn("Webhook " + req.path() + " received, but there are no listeners connected.")

            // returning an error should make it easier for client to redeliver later (when there is a listener)
            statusCode = HTTP_SERVICE_UNAVAILABLE
        }
        context.response()
                .setStatusCode(statusCode)
                .end("Received by " + APP_NAME + " (" + describe(listeners.size) + ")")
    }

    private fun handleListen(webSocket: ServerWebSocket) {
        webSocket.coroutineHandler { buffer: Buffer ->
            val msg = buffer.toJsonObject()
            val messageType = MessageType.valueOf(msg.getString(TYPE))
            when (messageType) {
                LOGIN -> handleLogin(msg, webSocket)
                PING -> handlePing(msg, webSocket)
                PONG -> handlePong(msg)
                else -> handleUnknownMessage(msg, webSocket)
            }
        }
    }

    private suspend fun handleLogin(msg: JsonObject, webSocket: ServerWebSocket) {
        val password = msg.getString(PASSWORD)
        if (passhash == null) {
            log.info("unverified websocket connection")
            val obj = JsonObject()
            obj.put(TYPE, SUCCESS)
            webSocket.writeTextMessage(obj.encode())
            registerWebsocket(webSocket)
        } else if (BCrypt.checkpw(password, passhash)) {
            log.info("password accepted")
            val obj = JsonObject()
            obj.put(TYPE, SUCCESS)
            webSocket.writeTextMessage(obj.encode())
            registerWebsocket(webSocket)
        } else {
            log.warn("password rejected")
            val obj = JsonObject()
            obj.put(TYPE, FAILED)
            webSocket.writeTextMessage(obj.encode())
            webSocket.close()
        }
    }

    private fun handlePing(msg: JsonObject, webSocket: ServerWebSocket) {
        val pingId = msg.getString(PING_ID)
        log.debug("received PING with id {}", pingId)
        val pong = JsonObject()
        pong.put(TYPE, PONG)
        pong.put(PING_ID, pingId)
        webSocket.writeTextMessage(pong.encode())
    }

    private fun handlePong(msg: JsonObject) {
        val pongId = msg.getString(PING_ID)
        // TODO check ping ID
        log.debug("received PONG with id {}", pongId)
    }


    private fun handleUnknownMessage(msg: JsonObject, webSocket: ServerWebSocket) {
        log.warn("unexpected message: {0}", msg)
        val obj = JsonObject()
        obj.put(TYPE, FAILED)
        webSocket.writeTextMessage(obj.encode())
        webSocket.close()
    }

    private suspend fun registerWebsocket(webSocket: ServerWebSocket) {
        // TODO enhancement: register specific webhook path using webSocket.path() or webSocket.query()
        val id = webSocket.textHandlerID()
        val clientIP = getClientIP(webSocket)
        log.info("Adding connection. ID: $id IP: $clientIP")
        localConnections.add(id)
        log.info("Total local connections: {0}", localConnections.size)
        val connections = connections
        awaitResult<Void> { connections.put(id, true, it) }
        log.info("Connection added: now {0} connections to cluster", fetchConnectionCount())

        webSocket.closeCoroutineHandler {
            log.info("Connection closed. ID: {0} IP: {1}", id, clientIP)
            localConnections.remove(id)
            log.info("Total local connections: {0}", localConnections.size)
            awaitResult<Boolean> { connections.remove(id, it) }
            log.info("Connection removed: now {0} connections to cluster", fetchConnectionCount())
        }
        webSocket.exceptionCoroutineHandler { e ->
            log.warn("Connection error. ID: {0} IP: {1}", e, id, clientIP)
            localConnections.remove(id)
            log.info("Total local connections: {0}", localConnections.size)
            awaitResult<Boolean> { connections.remove(id, it) }
            log.info("Broken connection removed: now {0} connections to cluster", fetchConnectionCount())
        }
    }

    private fun pingConnection(connection: String?) {
        // this is probably the correct way (ping frame triggers pong, closes websocket if no data received before idleTimeout in TCPSSLOptions):
        // WebSocketFrameImpl frame = new WebSocketFrameImpl(FrameType.PING, io.netty.buffer.Unpooled.copyLong(System.currentTimeMillis()));
        // webSocket.writeFrame(frame);

        val obj = JsonObject()
        obj.put(TYPE, PING)
        obj.put(PING_ID, System.currentTimeMillis().toString())
        eventBus.send(connection, obj.encode())
    }

    /*
     * Extension methods to simplify coroutine usage for various WebSocket handlers
     */
    private fun WebSocketBase.coroutineHandler(fn : suspend (Buffer) -> Unit) {
        handler { buffer ->
            launch(vertx.dispatcher()) {
                fn(buffer)
            }
        }
    }

    private fun WebSocketBase.closeCoroutineHandler(fn : suspend () -> Unit) {
        closeHandler {
            launch(vertx.dispatcher()) {
                fn()
            }
        }
    }

    private fun WebSocketBase.exceptionCoroutineHandler(fn : suspend (Throwable) -> Unit) {
        exceptionHandler { throwable ->
            launch(vertx.dispatcher()) {
                fn(throwable)
            }
        }
    }

}

private val APP_NAME = ProxyHookServer::class.java.name

// HTTP status codes
private val HTTP_OK = 200
//private val HTTP_NO_CONTENT = 204
//private val HTTP_INTERNAL_SERVER_ERROR = 500
//private val HTTP_NOT_IMPLEMENTED = 501
//private val HTTP_BAD_GATEWAY = 502
private val HTTP_SERVICE_UNAVAILABLE = 503
//private val HTTP_GATEWAY_TIMEOUT = 504

private val log = LoggerFactory.getLogger(ProxyHookServer::class.java)

private val localHostName: String by lazy {
    try {
        InetAddress.getLocalHost().hostName
    } catch (e: UnknownHostException) {
        log.warn("Unable to find hostname", e)
        "localhost"
    }
}

// visible for testing
internal fun describe(size: Int): String = describe(size.toLong())

// visible for testing
internal fun describe(size: Long): String = if (size == 1L) {
    "1 listener"
} else {
    "$size listeners"
}

private fun getClientIP(webSocket: ServerWebSocket): String {
    var clientIP: String? = webSocket.headers().get("X-Client-Ip")
    if (clientIP == null) clientIP = webSocket.headers().get("X-Forwarded-For")
    if (clientIP == null) clientIP = webSocket.remoteAddress().host()!!
    return clientIP
}

private fun encodeWebhook(req: HttpServerRequest, buffer: Buffer): String {
    val msg = JsonObject()
    msg.put(TYPE, WEBHOOK)
    msg.put(PATH, req.path())
    msg.put(QUERY, req.query())
    val headers = CaseInsensitiveHeaders().addAll(req.headers())
    msg.put(HOST, headers.get("Host"))
    headers.remove("Host")
    //                    headers.remove("Content-Length");
    // serialise MultiMap
    msg.put(HEADERS, multiMapToJson(headers))

    if (treatAsUTF8(headers.get(HttpHeaders.CONTENT_TYPE))) {
        // toString will blow up if not valid UTF-8
        msg.put(BUFFER_TEXT, buffer.toString())
    } else {
        msg.put(BUFFER, buffer.bytes)
    }
    return msg.encode()
}

// visible for testing
internal fun treatAsUTF8(contentType: String?): Boolean {
    if (contentType == null) return false // equiv. to application/octet-stream
    val contentTypeSplit = contentType.toLowerCase().split("; *".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
    // use the explicit charset if available:
    contentTypeSplit
            .filter { it.matches("charset=(utf-?8|ascii)".toRegex()) }
            .forEach { return true }
    // otherwise we infer charset based on the content type:
    return when (contentType) {
    // JSON only allows Unicode.
        "application/json",
            // XML defaults to Unicode.
            // An XML doc could specify another (non-Unicode) charset internally, but we don't support this.
        "application/xml",
            // Defaults to ASCII:
        "text/xml" -> true
    // If in doubt, treat as non-Unicode (or binary)
        else -> false
    }
}

/*
 * An extension method for simplifying coroutines usage with Vert.x Web routers
 */
@Suppress("Detekt.TooGenericExceptionCaught")
private fun Route.coroutineHandler(fn : suspend (RoutingContext) -> Unit) {
    handler { ctx ->
        launch(ctx.vertx().dispatcher()) {
            try {
                fn(ctx)
            } catch(e: Exception) {
                ctx.fail(e)
            }
        }
    }
}
