package org.zanata.proxyhook.client

import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.http.HttpServer
import io.vertx.core.logging.LoggerFactory
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.awaitResult
import kotlinx.coroutines.experimental.runBlocking
import org.asynchttpclient.DefaultAsyncHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockserver.client.proxy.ProxyClient
import org.mockserver.junit.ProxyRule
import org.mockserver.model.HttpRequest.request
import org.mockserver.verify.VerificationTimes.exactly
import org.mockserver.verify.VerificationTimes.once
import org.zanata.proxyhook.server.ProxyHookServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.MILLISECONDS

// We could use VertxUnitRunner, a RunTestOnContext rule and TestContext, but
// this way we can run client, server and webhook receiver on separate Vert.x
// instances.
class IntegrationTest {

    companion object {
        private val log = LoggerFactory.getLogger(IntegrationTest::class.java)
        private const val TEST_TIMEOUT_MS = 60_000L
        private const val webhookPayload = "This is the webhook payload."
    }

    // we use separate vert.x instances for each simulated tier
    // (in production they would be separate machines)
    lateinit private var server: Vertx
    lateinit private var webhook: Vertx
    lateinit private var client: Vertx

    @Rule @JvmField
    val proxyRule = ProxyRule(this)
    private lateinit var proxyClient: ProxyClient

    @Before
    fun before() = runBlocking {
        webhook = Vertx.vertx()
        client = Vertx.vertx()
    }

    @After
    fun after() = runBlocking<Unit> {
        // to minimise shutdown errors:
        // 1. we want the client to stop delivering before the webhook receiver stops
        // 2. we want the client to disconnect from server before server stops
        awaitResult<Void> { client.close(it) }
        awaitResult<Void> { webhook.close(it) }
        awaitResult<Void> { server.close(it) }
    }

    @Test
    fun rootDeployment() {
        server = Vertx.vertx()
        deliverProxiedWebhook(prefix = "")
        proxyClient.verifyZeroInteractions()
    }

    @Test
    fun rootDeploymentInInfinispanCluster() {
        server = runBlocking {
            awaitResult<Vertx> {
                val serverOpts = VertxOptions().apply {
                    isClustered = true
                    clusterManager = io.vertx.ext.cluster.infinispan.InfinispanClusterManager()
                    clusterHost = "localhost"
                    clusterPort = 0
                }
                Vertx.clusteredVertx(serverOpts, it)
            }
        }
        deliverProxiedWebhook(prefix = "")
        proxyClient.verifyZeroInteractions()
    }

    @Test
    fun rootDeploymentWithProxy() {
        server = Vertx.vertx()
        deliverProxiedWebhook(prefix = "", httpProxyHost = "localhost", httpProxyPort = proxyRule.httpPort)
//        proxyClient.dumpToLogAsJSON(request())
        proxyClient.verify(request(), once())
    }

    @Test
    fun subPathDeployment() {
        server = Vertx.vertx()
        deliverProxiedWebhook(prefix = "/proxyhook")
        proxyClient.verifyZeroInteractions()
    }

    private fun ProxyClient.verifyZeroInteractions() {
        verify(request(), exactly(0))
    }

    private fun deliverProxiedWebhook(prefix: String, httpProxyHost: String? = null, httpProxyPort: Int? = null): Unit = runBlocking {
        // this future will succeed if the test passes,
        // or fail if something goes wrong.
        val testFinished = CompletableFuture<Unit>()

        val (_, serverPort) = startServer(prefix)

        val webhookPort = createWebhookReceiver(testFinished)
        val receiveUrl = "http://localhost:${webhookPort.await()}/"
        val websocketUrl = "ws://localhost:${serverPort.await()}$prefix/listen"
        val postUrl = "http://localhost:${serverPort.await()}$prefix/webhook"
        // wait for proxyhook server and webhook receiver before starting client
        val client = startClient(testFinished, websocketUrl, receiveUrl, httpProxyHost, httpProxyPort)

        // wait for client login before sending webhook to proxyhook server
        client.await()
        log.info("client ready, preparing to POST webhook")

        postWebhookToServer(postUrl, testFinished)

        testFinished.get(TEST_TIMEOUT_MS, MILLISECONDS)
    }

    private suspend fun startServer(prefix: String): Pair<Future<String>, Future<Int>> {
        val actualPort = Future.future<Int>()
        val deploymentId = futureResult<String> { handler ->
            server.deployVerticle(ProxyHookServer(port = 0, prefix = prefix, actualPort = actualPort), handler)
        }
        return deploymentId to actualPort
    }

    private fun createWebhookReceiver(testFinished: CompletableFuture<Unit>): Future<Int> = future {
        val httpServer: HttpServer = awaitResult { handler ->
            webhook.createHttpServer().requestHandler { req ->
                req.response().statusCode = 200
                req.response().end()

                req.bodyHandler { buffer ->
                    val payload = buffer.toString(Charsets.UTF_8)
                    if (payload == webhookPayload) {
                        // we received the proxy webhook with its payload, so the test has passed:
                        testFinished.complete(Unit)
                    } else {
                        testFinished.completeExceptionally(AssertionError("wrong payload: $payload"))
                    }
                }
            }.listen(0, handler) // listen on random port
        }
        val actualPort = httpServer.actualPort()
        log.info("webhook receiver ready on port $actualPort")
        actualPort
    }

    private fun startClient(testFinished: CompletableFuture<Unit>, websocketUrl: String, receiveUrl: String, internalHttpProxyHost: String?, internalHttpProxyPort: Int?): Future<Unit> {
        log.info("deploying client")
        val clientReady = Future.future<Unit>()
        client.deployVerticle(ProxyHookClient(clientReady, listOf(websocketUrl), listOf(receiveUrl), internalHttpProxyHost = internalHttpProxyHost, internalHttpProxyPort = internalHttpProxyPort)) {
            if (it.failed()) {
                testFinished.completeExceptionally(it.cause())
            }
        }
        return clientReady
    }

    private fun postWebhookToServer(postUrl: String, testFinished: CompletableFuture<Unit>) {
        val httpClient = DefaultAsyncHttpClient()
        httpClient.preparePost(postUrl)
                .setBody(webhookPayload)
                .execute()
                .toCompletableFuture()
                .whenComplete { response, e ->
                    if (e != null) {
                        testFinished.completeExceptionally(e)
                    } else if (response != null && response.statusCode != 200) {
                        testFinished.completeExceptionally(AssertionError("bad response ${response.statusCode}: ${response.responseBody}"))
                    }
                }
    }

}
