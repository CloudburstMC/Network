package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JdkControlHttpTransportTest {
    final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    final java.util.concurrent.ExecutorService servers = Executors.newFixedThreadPool(4);
    final List<HttpServer> listeners = new ArrayList<>();
    final List<JdkControlHttpTransport> transports = new ArrayList<>();
    @AfterEach void close() {
        transports.forEach(JdkControlHttpTransport::close); listeners.forEach(server -> server.stop(0));
        timers.shutdownNow(); servers.shutdownNow();
    }
    JdkControlHttpTransport transport(HttpClient client, long timeout, int maximum) {
        var value = new JdkControlHttpTransport(client, timers, ControlClientClock.system(), maximum, timeout);
        transports.add(value); return value;
    }
    URI listen(String address, com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(address), 0), 0);
        listeners.add(server); server.setExecutor(servers); server.createContext("/", handler); server.start();
        return URI.create("http://" + (address.contains(":") ? "[" + address + "]" : address) + ":" + server.getAddress().getPort() + "/control/status");
    }
    static ControlSessionCodec.Request proof(URI endpoint) throws Exception {
        JsonObject value = ControlSessionCodecTest.vector("current-writer").getAsJsonObject("envelope").deepCopy();
        long now = System.currentTimeMillis();
        value.addProperty("audience", endpoint.getScheme() + "://" + endpoint.getRawAuthority());
        value.addProperty("encodedPathAndQuery", endpoint.getRawPath() + (endpoint.getRawQuery() == null ? "" : "?" + endpoint.getRawQuery()));
        value.addProperty("sentAt", now); value.addProperty("expiresAt", now + 30_000);
        return ControlSessionCodec.sign(ControlSessionCodec.decodeRequest(value.toString()), ControlSessionCodecTest.privateKey("machine"));
    }
    static void reply(HttpExchange exchange, int status, byte[] bytes) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
    static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(5, TimeUnit.SECONDS); }

    static ControlHttpCodec.Request operationProof(URI endpoint, byte[] body) throws Exception {
        long now = System.currentTimeMillis();
        String audience = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        var intent = ControlLifecycleCodec.intent(audience, "heartbeat", "host_http_operation", 1, 1, "intent_http_operation", body);
        return ControlHttpCodec.sign(new ControlHttpCodec.Request(1, audience, "POST", endpoint.getRawPath(), now, now + 30_000,
                intent, "session_http_operation", 1, "connection_http_operation", "https", List.of("request-response"),
                new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, "machine_http_operation", "")), ControlSessionCodecTest.privateKey("machine"));
    }

    @Test void sendsOriginalLifecycleBodyAndCanonicalProofHeaderForBothFamilies() throws Exception {
        for (String family : List.of("127.0.0.1", "::1")) {
            AtomicReference<byte[]> received = new AtomicReference<>(); AtomicReference<String> header = new AtomicReference<>();
            URI endpoint = listen(family, exchange -> {
                received.set(exchange.getRequestBody().readAllBytes()); header.set(exchange.getRequestHeaders().getFirst(ControlHttpCodec.PROOF_HEADER));
                reply(exchange, 202, "{\"receipt\":\"bounded-raw-result\"}".getBytes(StandardCharsets.UTF_8));
            });
            byte[] original = " {\"build\":\"α\"}\n".getBytes(StandardCharsets.UTF_8), retained = original.clone();
            var request = operationProof(endpoint, original);
            var pending = transport(HttpClient.newHttpClient(), 3000, 2).operation(endpoint, request, original);
            java.util.Arrays.fill(original, (byte) 'x');
            var reply = await(pending);
            assertEquals(202, reply.status()); assertEquals(endpoint, reply.responseUri()); assertArrayEquals(retained, received.get());
            assertEquals(org.cloudburstmc.netty.signaling.ProviderCrypto.base64(ControlHttpCodec.encode(request).getBytes(StandardCharsets.UTF_8)), header.get());
            assertEquals(request, ControlHttpCodec.decode(new String(java.util.Base64.getUrlDecoder().decode(header.get()), StandardCharsets.UTF_8)));
        }
    }

    @Test void refusesLifecycleBodyMismatchAndOversizeBeforeNetworkEffects() throws Exception {
        AtomicInteger hits = new AtomicInteger(); URI endpoint = listen("127.0.0.1", exchange -> { hits.incrementAndGet(); exchange.close(); });
        var request = operationProof(endpoint, "{}".getBytes(StandardCharsets.UTF_8));
        var transport = transport(HttpClient.newHttpClient(), 1000, 1);
        assertThrows(IllegalArgumentException.class, () -> transport.operation(endpoint, request, " {}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> transport.operation(endpoint, request, new byte[ControlLifecycleCodec.MAX_HTTP_BODY_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> transport.operation(endpoint.resolve("/other"), request, "{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, hits.get());
    }

    @Test void capsLifecycleResultResponsesAtTheSharedResultEnvelopeLimit() throws Exception {
        URI endpoint = listen("127.0.0.1", exchange -> reply(exchange, 200, new byte[ControlResultCodec.MAX_ENVELOPE_BYTES + 1]));
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> await(transport(HttpClient.newHttpClient(), 3000, 1).operation(endpoint, operationProof(endpoint, body), body)));
    }

    @Test void sendsExactSignedBytesAndReportsActualUriForBothFamilies() throws Exception {
        for (String family : List.of("127.0.0.1", "::1")) {
            AtomicReference<byte[]> received = new AtomicReference<>(); AtomicReference<String> target = new AtomicReference<>();
            URI plain = listen(family, exchange -> {
                received.set(exchange.getRequestBody().readAllBytes()); target.set(exchange.getRequestURI().toASCIIString());
                assertEquals("POST", exchange.getRequestMethod()); reply(exchange, 200, "{\"raw\":true}".getBytes(StandardCharsets.UTF_8));
            });
            URI endpoint = URI.create(plain + "?a=%2F&b=1"); var request = proof(endpoint);
            var response = await(transport(HttpClient.newHttpClient(), 3000, 2).bootstrap(endpoint, request));
            assertEquals(ControlSessionCodec.encode(request), new String(received.get(), StandardCharsets.UTF_8));
            assertEquals(endpoint.getRawPath() + "?a=%2F&b=1", target.get());
            assertEquals(new ControlClientIo.HttpReply(endpoint, "POST", endpoint, 200, "{\"raw\":true}"), response);
        }
    }

    @Test void refusesEndpointMismatchAndRedirectEnabledClients() throws Exception {
        AtomicInteger hits = new AtomicInteger(); URI endpoint = listen("127.0.0.1", exchange -> { hits.incrementAndGet(); exchange.close(); });
        var request = proof(endpoint); var client = transport(HttpClient.newHttpClient(), 1000, 1);
        assertThrows(IllegalArgumentException.class, () -> client.bootstrap(endpoint.resolve("/other"), request));
        assertThrows(IllegalArgumentException.class, () -> client.bootstrap(URI.create(endpoint + "#fragment"), request));
        assertThrows(IllegalArgumentException.class, () -> client.bootstrap(URI.create(endpoint.toString().replace("127.0.0.1", "localhost")), request));
        assertThrows(IllegalArgumentException.class, () -> transport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(), 1000, 1));
        assertEquals(0, hits.get());
    }

    @Test void returnsRedirectStatusWithoutFollowingIt() throws Exception {
        AtomicInteger destinationHits = new AtomicInteger();
        URI destination = listen("127.0.0.1", exchange -> { destinationHits.incrementAndGet(); reply(exchange, 200, "{}".getBytes()); });
        URI endpoint = listen("127.0.0.1", exchange -> { exchange.getResponseHeaders().set("Location", destination.toString()); reply(exchange, 307, "{}".getBytes()); });
        var result = await(transport(HttpClient.newHttpClient(), 3000, 1).bootstrap(endpoint, proof(endpoint)));
        assertEquals(307, result.status()); assertEquals(endpoint, result.responseUri()); assertEquals(0, destinationHits.get());
    }

    @Test void boundsKnownAndChunkedBodiesAndRejectsMalformedUtf8() throws Exception {
        for (String mode : List.of("length", "chunked", "utf8", "type", "encoding")) {
            URI endpoint = listen("127.0.0.1", exchange -> {
                byte[] bytes = mode.equals("utf8") ? new byte[]{(byte) 0xc3, 0x28} : new byte[16_385];
                exchange.getResponseHeaders().set("Content-Type", mode.equals("type") ? "text/plain" : "application/json");
                if (mode.equals("encoding")) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, mode.equals("chunked") ? 0 : bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); } catch (java.io.IOException canceled) { }
            });
            var result = transport(HttpClient.newHttpClient(), 3000, 1).bootstrap(endpoint, proof(endpoint));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> await(result), mode);
        }
    }

    @Test void deadlineIncludesTheBodyAndCapacityDoesNotQueue() throws Exception {
        CountDownLatch headers = new CountDownLatch(1), finish = new CountDownLatch(1); AtomicInteger hits = new AtomicInteger();
        URI endpoint = listen("127.0.0.1", exchange -> {
            hits.incrementAndGet(); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) { out.write('{'); out.flush(); headers.countDown(); finish.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); } catch (java.io.IOException canceled) { }
        });
        var client = transport(HttpClient.newHttpClient(), 700, 1); var request = proof(endpoint);
        var first = client.bootstrap(endpoint, request); assertTrue(headers.await(2, TimeUnit.SECONDS));
        var excess = client.bootstrap(endpoint, request);
        assertTrue(excess.toCompletableFuture().isCompletedExceptionally()); assertEquals(1, hits.get());
        var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> await(first));
        assertInstanceOf(java.net.http.HttpTimeoutException.class, error.getCause()); finish.countDown();
    }

    @Test void closeCancelsAnInFlightBodyAndRefusesNewRequests() throws Exception {
        CountDownLatch headers = new CountDownLatch(1), finish = new CountDownLatch(1);
        URI endpoint = listen("127.0.0.1", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) { out.write('{'); out.flush(); headers.countDown(); finish.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); } catch (java.io.IOException canceled) { }
        });
        var client = transport(HttpClient.newHttpClient(), 3000, 1); var request = proof(endpoint);
        var pending = client.bootstrap(endpoint, request); assertTrue(headers.await(2, TimeUnit.SECONDS)); client.close();
        assertThrows(java.util.concurrent.ExecutionException.class, () -> await(pending));
        assertTrue(client.bootstrap(endpoint, request).toCompletableFuture().isCompletedExceptionally()); finish.countDown();
    }

    @Test void bodySubscriberOwnsChunksAndCancelsOverflow() throws Exception {
        AtomicInteger cancels = new AtomicInteger(); Flow.Subscription subscription = new Flow.Subscription() {
            public void request(long n) { } public void cancel() { cancels.incrementAndGet(); }
        };
        var body = new JdkControlHttpTransport.BoundedBody(5); body.onSubscribe(subscription);
        byte[] first = "he".getBytes(StandardCharsets.UTF_8); body.onNext(List.of(ByteBuffer.wrap(first))); first[0] = 'x';
        body.onNext(List.of(ByteBuffer.wrap("llo".getBytes(StandardCharsets.UTF_8)))); body.onComplete();
        assertEquals("hello", await(body.getBody()));
        var overflow = new JdkControlHttpTransport.BoundedBody(5); overflow.onSubscribe(subscription);
        overflow.onNext(List.of(ByteBuffer.wrap(new byte[6])));
        assertThrows(java.util.concurrent.ExecutionException.class, () -> await(overflow.getBody())); assertEquals(1, cancels.get());
    }

    @Test void completedRequestReleasesCapacityBeforeSynchronousChainedCallback() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1), send = new CountDownLatch(1); AtomicInteger hits = new AtomicInteger();
        URI endpoint = listen("127.0.0.1", exchange -> {
            hits.incrementAndGet(); arrived.countDown();
            try { assertTrue(send.await(2, TimeUnit.SECONDS)); reply(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)); }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
        });
        var client = transport(HttpClient.newHttpClient(), 3000, 1); var request = proof(endpoint);
        var first = client.bootstrap(endpoint, request); assertTrue(arrived.await(2, TimeUnit.SECONDS));
        var chained = first.thenCompose(ignored -> client.bootstrap(endpoint, request)); send.countDown();
        assertEquals(200, await(chained).status()); assertEquals(2, hits.get());
    }

    @Test void authorityUsesExactSignedBytesAndItsSmallerBodyLimit() throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>(); AtomicInteger bytes = new AtomicInteger(8192);
        URI endpoint = listen("127.0.0.1", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes()); reply(exchange, 200, new byte[bytes.get()]);
        }).resolve("/control/authority");
        var vector = ControlAuthorityCodecTest.request("ws-request"); long now = System.currentTimeMillis();
        var request = new ControlAuthorityCodec.Request(vector.version(), vector.kind(), vector.requestId(),
                "http://" + endpoint.getRawAuthority(), vector.instanceId(), vector.generation(), vector.writer(), vector.capabilities(),
                now, now + 30_000, "POST", endpoint.getRawPath(), now + 300_000, vector.authentication());
        request = ControlAuthorityCodec.sign(request, ControlSessionCodecTest.privateKey("machine"));
        var client = transport(HttpClient.newHttpClient(), 3000, 1);
        assertEquals(8192, await(client.authority(endpoint, request)).body().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(ControlAuthorityCodec.encode(request), new String(received.get(), StandardCharsets.UTF_8));
        bytes.set(8193); var excess = client.authority(endpoint, request);
        assertThrows(java.util.concurrent.ExecutionException.class, () -> await(excess));
    }

    @Test void websocketLinkSendsExactCanonicalProofHeaderAndSubprotocol() throws Exception {
        AtomicReference<String> header = new AtomicReference<>(), protocol = new AtomicReference<>(), target = new AtomicReference<>();
        URI plain = listen("127.0.0.1", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("Nxs-Control-Proof"));
            protocol.set(exchange.getRequestHeaders().getFirst("Sec-WebSocket-Protocol")); target.set(exchange.getRequestURI().toString());
            reply(exchange, 426, "{}".getBytes(StandardCharsets.UTF_8)); // Capture the real upgrade without creating a second WS fixture.
        }).resolve("/control/upgrade");
        var value = ControlSessionCodecTest.vector("upgrade-1").getAsJsonObject("envelope").deepCopy(); long now = System.currentTimeMillis();
        value.addProperty("audience", "http://" + plain.getRawAuthority()); value.addProperty("encodedPathAndQuery", plain.getRawPath());
        value.addProperty("sentAt", now); value.addProperty("expiresAt", now + 30_000);
        var request = ControlSessionCodec.sign(ControlSessionCodec.decodeRequest(value.toString()), ControlSessionCodecTest.privateKey("machine"));
        var timeout = java.time.Duration.ofSeconds(3);
        var limits = new JdkWebSocketTransport.Limits(4096, 64, 8, 32768, timeout, timeout, timeout, timeout);
        var link = JdkControlLink.connect(HttpClient.newHttpClient(), URI.create("ws://" + plain.getRawAuthority() + plain.getRawPath()), request,
                limits, servers, timers, ignored -> { });
        try {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> await(link.opened()));
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ControlSessionCodec.encode(request).getBytes(StandardCharsets.UTF_8)), header.get());
            assertEquals("nethernet-control-v1", protocol.get()); assertEquals("/control/upgrade", target.get());
        } finally { link.abort(); }
    }

    @Test void bodySubscriptionIsCanceledBeforeFailureCallbacksCanBlock() throws Exception {
        AtomicInteger cancels = new AtomicInteger(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var body = new JdkControlHttpTransport.BoundedBody(5);
        body.onSubscribe(new Flow.Subscription() { public void request(long count) { } public void cancel() { cancels.incrementAndGet(); body.onError(new java.io.IOException("reentrant cancellation")); } });
        body.getBody().whenComplete((value, error) -> { entered.countDown(); waitFor(release); });
        var failing = servers.submit(() -> body.onError(new java.io.IOException("failed")));
        try { assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(1, cancels.get()); }
        finally { release.countDown(); failing.get(2, TimeUnit.SECONDS); }
    }

    @Test void closeCancelsEveryUnderlyingRequestBeforeAnyPublicFailureCallback() throws Exception {
        var fake = new PendingClient(); var client = transport(fake, 3000, 2);
        URI endpoint = URI.create("http://127.0.0.1:45678/control/status"); var request = proof(endpoint);
        var one = client.bootstrap(endpoint, request); var two = client.bootstrap(endpoint, request);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        one.whenComplete((value, error) -> { entered.countDown(); waitFor(release); });
        two.whenComplete((value, error) -> { entered.countDown(); waitFor(release); });
        var closing = servers.submit(client::close);
        try { assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(fake.pending.stream().allMatch(java.util.concurrent.CompletableFuture::isCancelled)); }
        finally { release.countDown(); closing.get(2, TimeUnit.SECONDS); }
    }

    @Test void timeoutCancelsUnderlyingWorkBeforePublicFailureCallbackAndDerivedCancellationIsIsolated() throws Exception {
        var fake = new PendingClient(); var client = transport(fake, 150, 1);
        URI endpoint = URI.create("http://127.0.0.1:45678/control/status"); var request = proof(endpoint);
        var response = client.bootstrap(endpoint, request); response.toCompletableFuture().cancel(true);
        assertFalse(fake.pending.get(0).isDone());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        response.whenComplete((value, error) -> { entered.countDown(); waitFor(release); });
        try { assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(fake.pending.get(0).isCancelled()); }
        finally { release.countDown(); }
    }

    @Test void closeBeforeSendAsyncReturnsCancelsItsLateAttachedFuture() throws Exception {
        var fake = new PendingClient(); var client = transport(fake, 3000, 1);
        URI endpoint = URI.create("http://127.0.0.1:45678/control/status"); var request = proof(endpoint);
        CountDownLatch sending = new CountDownLatch(1), resume = new CountDownLatch(1);
        fake.beforeReturn = () -> { sending.countDown(); waitFor(resume); };
        var starting = servers.submit(() -> client.bootstrap(endpoint, request));
        try {
            assertTrue(sending.await(2, TimeUnit.SECONDS)); client.close();
            assertTrue(client.bootstrap(endpoint, request).toCompletableFuture().isCompletedExceptionally());
        } finally { resume.countDown(); }
        var response = starting.get(2, TimeUnit.SECONDS);
        assertThrows(java.util.concurrent.ExecutionException.class, () -> await(response)); assertTrue(fake.pending.get(0).isCancelled());
    }

    @Test void closeWinsWhileCompletedResponseProvenanceIsStillBeingChecked() throws Exception {
        var fake = new PendingClient(); var client = transport(fake, 3000, 1);
        URI endpoint = URI.create("http://127.0.0.1:45678/control/status"); var response = client.bootstrap(endpoint, proof(endpoint));
        CountDownLatch checking = new CountDownLatch(1), resume = new CountDownLatch(1);
        HttpResponse<String> reply = new HttpResponse<>() {
            public int statusCode() { return 200; }
            public HttpRequest request() { return fake.requests.get(0); }
            public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
            public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (a,b) -> true); }
            public String body() { return "{}"; }
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            public URI uri() { checking.countDown(); waitFor(resume); return endpoint; }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
        var completing = servers.submit(() -> fake.pending.get(0).complete(reply));
        try {
            assertTrue(checking.await(2, TimeUnit.SECONDS)); client.close();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> await(response));
        } finally { resume.countDown(); completing.get(2, TimeUnit.SECONDS); }
    }

    static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test release"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    static final class PendingClient extends HttpClient {
        final HttpClient delegate = HttpClient.newHttpClient();
        final List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
        final List<HttpRequest> requests = new ArrayList<>();
        Runnable beforeReturn = () -> { };
        public java.util.Optional<java.net.CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        public java.util.Optional<java.time.Duration> connectTimeout() { return delegate.connectTimeout(); }
        public Redirect followRedirects() { return delegate.followRedirects(); }
        public java.util.Optional<java.net.ProxySelector> proxy() { return delegate.proxy(); }
        public SSLContext sslContext() { return delegate.sslContext(); }
        public javax.net.ssl.SSLParameters sslParameters() { return delegate.sslParameters(); }
        public java.util.Optional<java.net.Authenticator> authenticator() { return delegate.authenticator(); }
        public Version version() { return delegate.version(); }
        public java.util.Optional<java.util.concurrent.Executor> executor() { return delegate.executor(); }
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        @SuppressWarnings("unchecked")
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            var future = new java.util.concurrent.CompletableFuture<HttpResponse<String>>(); pending.add(future); requests.add(request); beforeReturn.run();
            return (java.util.concurrent.CompletableFuture<HttpResponse<T>>) (Object) future;
        }
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
            return sendAsync(request, handler);
        }
    }

    @Test void usesNormalCertificateTrustAndHostnameVerification() throws Exception {
        var certificate = new SelfSignedCertificate("localhost");
        try {
            KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType()); keys.load(null, null);
            keys.setKeyEntry("server", certificate.key(), new char[0], new java.security.cert.Certificate[]{certificate.cert()});
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); kmf.init(keys, new char[0]);
            SSLContext serverTls = SSLContext.getInstance("TLS"); serverTls.init(kmf.getKeyManagers(), null, null);
            HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0); listeners.add(server);
            server.setHttpsConfigurator(new HttpsConfigurator(serverTls)); server.setExecutor(servers);
            server.createContext("/", exchange -> reply(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8))); server.start();
            URI endpoint = URI.create("https://localhost:" + server.getAddress().getPort() + "/control/status");
            var request = proof(endpoint);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> await(transport(HttpClient.newHttpClient(), 3000, 1).bootstrap(endpoint, request)));
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null); trust.setCertificateEntry("local", certificate.cert());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init(trust);
            SSLContext clientTls = SSLContext.getInstance("TLS"); clientTls.init(null, tmf.getTrustManagers(), null);
            var client = transport(HttpClient.newBuilder().sslContext(clientTls).build(), 3000, 1);
            assertEquals(200, await(client.bootstrap(endpoint, request)).status());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            assertEquals(200, await(client.operation(endpoint, operationProof(endpoint, body), body)).status());
            URI wrongName = URI.create(endpoint.toString().replace("localhost", "127.0.0.1"));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> await(client.bootstrap(wrongName, proof(wrongName))));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> await(client.operation(wrongName, operationProof(wrongName, body), body)));
        } finally { certificate.delete(); }
    }
}
