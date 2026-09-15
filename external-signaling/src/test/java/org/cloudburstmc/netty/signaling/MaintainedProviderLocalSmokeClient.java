package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection;
import tel.schich.libdatachannel.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.admission.*;
import org.cloudburstmc.netty.signaling.control.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.*;

/** Private dual-stack metadata lab. Synthetic mapped addresses never represent tested public reachability. */
public final class MaintainedProviderLocalSmokeClient {
    private final JsonObject config;
    private final long deadline;
    private final List<Host> hosts = new ArrayList<>();
    private final DefaultEventLoopGroup group = new DefaultEventLoopGroup(2);
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "maintained-smoke-stop"); t.setDaemon(true); return t; });
    private Path trustStore;
    private static final class CountedTransport implements ProviderTransport {
        final NativeProviderTransport actual; final AtomicInteger installs = new AtomicInteger(), commits = new AtomicInteger();
        CountedTransport(NativeProviderTransport actual) { this.actual = actual; }
        @Override public boolean supportsAdmissionStaging() { return actual.supportsAdmissionStaging(); }
        @Override public AdmissionUpdate beginAdmissionUpdate(long deadline) { return actual.beginAdmissionUpdate(deadline); }
        @Override public CompletionStage<Void> installTicketKeys(AdmissionUpdate update, List<TicketKey> keys) { installs.incrementAndGet(); return actual.installTicketKeys(update, keys); }
        @Override public CompletionStage<ApplyResult> commitAdmissionUpdate(AdmissionUpdate update, Runnable guard) { commits.incrementAndGet(); return actual.commitAdmissionUpdate(update, guard); }
        @Override public CompletionStage<JsonObject> hostProfile() { return actual.hostProfile(); }
        @Override public CompletionStage<HostProfileSnapshot> captureHostProfile() { return actual.captureHostProfile(); }
        @Override public boolean supportsNativeIdentityCapture() { return actual.supportsNativeIdentityCapture(); }
        @Override public NativeIdentitySnapshot captureNativeIdentity() { return actual.captureNativeIdentity(); }
        @Override public boolean supportsMaintainedCandidateLeases() { return actual.supportsMaintainedCandidateLeases(); }
        @Override public CandidateLeaseSnapshot maintainCandidateLeases(boolean allowed) { return actual.maintainCandidateLeases(allowed); }
        @Override public void candidateControlSynchronized() { actual.candidateControlSynchronized(); }
        @Override public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) { installs.incrementAndGet(); return actual.installTicketKeys(keys); }
        @Override public CompletionStage<ApplyResult> applyState(String state) { return actual.applyState(state); }
        @Override public boolean supportsGameOutcomes() { return actual.supportsGameOutcomes(); }
        @Override public List<JsonObject> pollEvents() { return actual.pollEvents(); }
        @Override public List<JsonObject> pollEvents(int max) { return actual.pollEvents(max); }
        @Override public CompletionStage<Void> drain() { return actual.drain(); }
        @Override public CompletionStage<Void> close() { return actual.close(); }
    }
    private final class Host {
        final String id, mode; final Path directory; final NativeProviderTransport nativeTransport; final CountedTransport counted; final ProviderClient client;
        final CompletableFuture<JsonObject> started;
        final List<Peer> peers = new ArrayList<>();
        final AtomicInteger persistenceFailures = new AtomicInteger();
        String writerSeen, refreshPhase; boolean ready;
        CompletableFuture<JsonObject> refresh;
        Host(JsonObject value) throws Exception {
            id = string(value, "hostId"); mode = string(value, "transport");
            if (!id.matches("[A-Za-z0-9_-]{1,128}") || !Set.of("https", "websocket").contains(mode)) throw new IllegalArgumentException("Invalid fixture host");
            directory = Path.of(string(config, "journalRoot")).resolve(id);
            var seed = value.getAsJsonObject("reportingSeed");
            var reporting = new ProviderControlConfiguration.ReportingSeed(number(seed, "generation"), number(seed, "appliedRevision"), string(seed, "reportedState"));
            var route = config.getAsJsonObject("routes"); var operations = new HashMap<String, URI>();
            route.getAsJsonObject("operations").entrySet().forEach(entry -> operations.put(entry.getKey(), URI.create(entry.getValue().getAsString())));
            var control = new ProviderControlConfiguration(new ControlClientCoordinator.Config(string(config, "origin"), uri(route, "prepare"), uri(route, "activate"), uri(route, "status"),
                    uri(route, "upgrade"), uri(route, "authority"), operations, mode, List.of("request-response"), 900000, 30000, 200, 10000, route.has("cancelIntent") ? uri(route, "cancelIntent") : null),
                    List.of(new ControlFrameCodec.VerificationKey(ControlFrameCodec.KeyFamily.PROVIDER_CONTROL, string(config, "providerKeyId"), ProviderCrypto.publicKey(config.getAsJsonObject("providerPublicKeyJwk")),
                            number(config, "validFrom"), number(config, "validUntil"))), reporting, ProviderControlConfiguration.NativeOwnership.ISSUED, ProviderControlConfiguration.CandidatePublication.MAINTAINED);
            var nativeConfig = config.getAsJsonObject("native"); String address = string(nativeConfig, "bindAddress");
            if (!Set.of("127.0.0.1", "::1", "::").contains(address)) throw new IllegalArgumentException("Only isolated loopback native fixture binds are allowed");
            int port = Math.toIntExact(number(value, "udpPort")); if (port < 1 || port > 65535) throw new IllegalArgumentException("Fixed UDP fixture port required");
            var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
                @Override protected void initChannel(AdmittedNetherNetChildChannel channel) {
                    channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                        private boolean reliable = true;
                        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
                            if (event instanceof NetherNetPacket.Delivery delivery) reliable = delivery.reliable();
                            else super.userEventTriggered(ctx, event);
                        }
                        @Override protected void channelRead0(ChannelHandlerContext ctx, ByteBuf bytes) { ctx.writeAndFlush(new NetherNetPacket(bytes.retain(), reliable)); }
                    });
                }
            });
            var bind = new InetSocketAddress(InetAddress.getByName(address), port);
            var servers = new EnumMap<EndpointSelection.Family, InetSocketAddress>(EndpointSelection.Family.class);
            for (var item : nativeConfig.getAsJsonArray("stunServers")) {
                var server = item.getAsJsonObject(); var ip = InetAddress.getByName(string(server, "address"));
                if (!ip.isLoopbackAddress()) throw new IllegalArgumentException("Local STUN responders required");
                servers.put(ip instanceof Inet6Address ? EndpointSelection.Family.IPV6 : EndpointSelection.Family.IPV4,
                        new InetSocketAddress(ip, Math.toIntExact(number(server, "port"))));
            }
            if (servers.size() != 2 || !address.equals("::")) throw new IllegalArgumentException("Dual-stack maintained fixture required");
            nativeTransport = NativeProviderTransport.openControlledMaintained(bootstrap,
                    EndpointSelection.select(bind, List.of(), List.of()), servers, Path.of(string(nativeConfig, "certificate")),
                    Path.of(string(nativeConfig, "privateKey")), AdmissionGate.Limits.defaults()).toCompletableFuture().get(8, TimeUnit.SECONDS);
            counted = new CountedTransport(nativeTransport);
            ProviderStateStore store = null;
            try {
                if (nativeTransport.channel().isServing()) throw new IllegalStateException("Native listener started with admission enabled");
                store = new ProviderStateStore(directory);
                if (store.read().isEmpty()) {
                    var state = new JsonObject(); state.addProperty("provider", string(config, "origin")); state.addProperty("sequence", number(value, "sequence"));
                    state.addProperty("privateKey", string(value, "privateKeyPkcs8")); state.add("publicKeyJwk", value.get("publicKeyJwk").deepCopy()); state.add("ticketKeys", new JsonArray());
                    var registration = new JsonObject(); registration.addProperty("provider", string(config, "origin")); registration.addProperty("profile", "nxs-admission-v1");
                    registration.addProperty("instanceId", id); registration.addProperty("registrationId", string(value, "registrationId")); registration.addProperty("keyId", string(value, "keyId"));
                    registration.addProperty("leaseGeneration", reporting.generation()); state.add("registration", registration); store.write(state);
                }
                client = new ProviderClient(new ProviderClient.Configuration(URI.create(string(config, "origin")), "nxs-admission-v1", "Local controlled fixture",
                        ProviderClient.NEW_SERVICE, ProviderClient.ANONYMOUS_PROOF_OF_WORK, null, null, null, Map.of(), control), store, counted, () -> null,
                        () -> new ProviderClient.Health(true, true, 10, 0, "fixture", "fixture"), diagnostic -> {
                            if (diagnostic.equals("controlled_application_persistence_failed")) persistenceFailures.incrementAndGet();
                        });
                emit("starting", id, mode, null); started = client.start();
            } catch (Throwable failure) {
                nativeTransport.close().toCompletableFuture().get(8, TimeUnit.SECONDS); if (store != null) store.close(); throw failure;
            }
        }
        JsonObject application() throws Exception { return ControlledProviderJson.parse(Files.readString(directory.resolve("provider-state.json")), 262144).getAsJsonObject("controlApplication"); }
        void observe() throws Exception {
            Path journal = directory.resolve("control-session/provider-state.json");
            if (Files.isRegularFile(journal)) {
                var value = ControlledProviderJson.parse(Files.readString(journal), 196608);
                if (value.has("grant")) {
                    var writer = value.getAsJsonObject("writer");
                    if (!writer.toString().equals(writerSeen)) { writerSeen = writer.toString(); var event = new JsonObject(); event.addProperty("sessionEpoch", number(writer, "sessionEpoch")); emit("writer_observed", id, mode, event); }
                }
            }
            if (!ready && started.isDone()) { started.get(); emit("ready", id, mode, fields()); ready = true; }
            if (refresh != null && refresh.isDone()) {
                try { refresh.get(); }
                catch (ExecutionException failure) {
                    if (!"save-fault".equals(refreshPhase)) throw failure;
                    if (nativeTransport.channel().isServing()) throw new IllegalStateException("Failed application save left native admission enabled");
                    if (persistenceFailures.get() != 1) throw new IllegalStateException("Expected one actual application persistence failure");
                    var event = failureType(failure); event.addProperty("nativeServing", false);
                    event.addProperty("persistenceFailureObserved", true);
                    var app = application(); event.add("candidateLeaseReceipt", app.has("candidateLeaseReceipt") ? app.get("candidateLeaseReceipt").deepCopy() : JsonNull.INSTANCE);
                    emit("save_failed", id, mode, event); refresh = null; return;
                }
                var event = fields(); event.addProperty("checkpoint", refreshPhase);
                for (var peer : peers) peer.exchange();
                event.addProperty("peerExchanges", peers.size()); emit("checkpoint", id, mode, event); refresh = null;
            }
        }
        JsonObject fields() throws Exception {
            var app = application(); var basis = ControlStateCodec.decodeAppliedBasis(string(app, "basis"));
            var actual = nativeTransport.hostProfile().toCompletableFuture().get(3, TimeUnit.SECONDS);
            var owner = CandidateLeaseCodec.decodeNativeOwner(app.getAsJsonObject("nativeOwnerReceipt").get("owner").toString());
            if (!nativeTransport.channel().isServing() || !actual.equals(app.get("profile"))
                    || !owner.nativeIncarnation().equals(nativeTransport.captureNativeIdentity().incarnation()))
                throw new IllegalStateException("Completed readiness differs from actual native application");
            var journal = ControlledProviderJson.parse(Files.readString(directory.resolve("control-session/provider-state.json")), 196608);
            var value = new JsonObject(); value.addProperty("nativeServing", true); value.addProperty("nativeProfileMatches", true);
            value.addProperty("sessionEpoch", number(journal.getAsJsonObject("writer"), "sessionEpoch"));
            value.addProperty("basisSha256", ControlStateCodec.appliedBasisDigest(basis)); value.addProperty("profileRevision", string(app, "profileRevision"));
            value.add("profile", actual); value.add("nativeOwner", JsonParser.parseString(CandidateLeaseCodec.encodeNativeOwner(owner)));
            value.add("candidateLeaseReceipt", app.has("candidateLeaseReceipt") ? app.get("candidateLeaseReceipt").deepCopy() : JsonNull.INSTANCE);
            value.addProperty("keyInstalls", counted.installs.get()); value.addProperty("applicationCommits", counted.commits.get());
            value.addProperty("creationAttempts", nativeTransport.channel().creationAttempts()); value.addProperty("liveNativePeers", nativeTransport.channel().liveNativePeers());
            value.addProperty("udpPort", ((InetSocketAddress) nativeTransport.channel().localAddress()).getPort());
            return value;
        }
        void checkpoint(String phase) {
            if (!ready || refresh != null || !phase.matches("[a-z0-9_-]{1,48}")) throw new IllegalStateException("Unexpected checkpoint");
            refreshPhase = phase; refresh = client.readiness();
        }
        void startPeers() throws Exception {
            if (!ready || refresh != null || !peers.isEmpty()) throw new IllegalStateException("Unexpected peer start");
            for (var ip : List.of("127.0.0.1", "::1")) peers.add(new Peer(this, ip));
            for (var peer : peers) peer.exchange();
            var event = fields(); event.addProperty("peerExchanges", peers.size()); emit("peers_started", id, mode, event);
        }
        void stopPeers() throws Exception {
            for (var peer : peers) peer.close(); peers.clear();
            emit("peers_stopped", id, mode, null);
        }
        void exchangePeers() throws Exception {
            if (peers.size() != 2) throw new IllegalStateException("Expected both family peers");
            for (var peer : peers) peer.exchange();
            var event = new JsonObject(); event.addProperty("peerExchanges", peers.size());
            event.addProperty("liveNativePeers", nativeTransport.channel().liveNativePeers());
            event.addProperty("creationAttempts", nativeTransport.channel().creationAttempts());
            event.addProperty("nativeServing", nativeTransport.channel().isServing());
            event.addProperty("nativeIncarnation", nativeTransport.captureNativeIdentity().incarnation());
            event.add("profile", nativeTransport.hostProfile().toCompletableFuture().get(3, TimeUnit.SECONDS));
            emit("peers_exchanged", id, mode, event);
        }
        void stop() throws Exception {
            stopPeers(); client.stop().toCompletableFuture().get(12, TimeUnit.SECONDS);
            nativeTransport.close().toCompletableFuture().get(8, TimeUnit.SECONDS);
            if (nativeTransport.channel().isActive() || nativeTransport.channel().liveNativePeers() != 0) throw new IllegalStateException("Native fixture did not terminate");
            emit("stopped", id, mode, null);
        }
    }
    private MaintainedProviderLocalSmokeClient(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536
                || !Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE).containsAll(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))) throw new IllegalArgumentException("Private fixture config required");
        config = ControlledProviderJson.parse(Files.readString(path), 65536);
        long executionMillis = number(config, "executionMillis");
        if (executionMillis < 180000 || executionMillis > 900000) throw new IllegalArgumentException("Invalid fixed execution bound");
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(executionMillis);
        if (!string(config, "origin").matches("https://127\\.0\\.0\\.1:[1-9][0-9]{0,4}")) throw new IllegalArgumentException("Explicit local HTTPS fixture required");
        var trust = KeyStore.getInstance("PKCS12"); trust.load(null, null);
        try (var certificate = Files.newInputStream(Path.of(string(config, "caCertificate")))) { trust.setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(certificate)); }
        Files.createDirectories(Path.of(string(config, "journalRoot")), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        trustStore = Files.createTempFile(Path.of(string(config, "journalRoot")), "fixture-trust-", ".p12", java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        String password = UUID.randomUUID().toString(); try (var out = Files.newOutputStream(trustStore)) { trust.store(out, password.toCharArray()); }
        // This new process uses the public test CA through standard JDK TLS. Production code has no TLS override.
        System.setProperty("javax.net.ssl.trustStore", trustStore.toString()); System.setProperty("javax.net.ssl.trustStoreType", "PKCS12"); System.setProperty("javax.net.ssl.trustStorePassword", password);
    }
    private void run() throws Exception {
        var values = config.getAsJsonArray("hosts"); if (values.size() != 2) throw new IllegalArgumentException("Exactly two carrier hosts required");
        for (var value : values) hosts.add(new Host(value.getAsJsonObject()));
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)); var command = reader.submit(input::readLine);
        while (System.nanoTime() < deadline) {
            for (var host : hosts) host.observe();
            if (command.isDone()) {
                String value = command.get();
                if ("stop".equals(value)) return;
                if (value != null && value.startsWith("checkpoint:")) for (var host : hosts) host.checkpoint(value.substring(11));
                else if ("peers-start".equals(value)) for (var host : hosts) host.startPeers();
                else if ("peers-stop".equals(value)) for (var host : hosts) host.stopPeers();
                else if ("peers-exchange".equals(value)) for (var host : hosts) host.exchangePeers();
                else throw new IllegalArgumentException("Unknown private fixture command");
                command = reader.submit(input::readLine);
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new TimeoutException("Maintained fixture fixed deadline expired");
    }
    /** Normal signed admission ticket, restricted to loopback answer delivery in this fixture. */
    private final class Peer implements AutoCloseable {
        final PeerConnection connection;
        final DataChannel reliable, unreliable;
        final AtomicInteger reliableEchoes = new AtomicInteger(), unreliableEchoes = new AtomicInteger();
        Peer(Host host, String ip) throws Exception {
            connection = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true)
                    .withBindAddress(InetAddress.getByName(ip)), Runnable::run);
            reliable = connection.createDataChannel("ReliableDataChannel");
            unreliable = connection.createDataChannel("UnreliableDataChannel", DataChannelInitSettings.DEFAULT.withReliability(new DataChannelReliability(true, true, 0, 0)));
            reliable.onMessage.register(DataChannelCallback.Message.handleBinary((channel, bytes) -> {
                if (bytes.remaining() == 2 && bytes.get() == 0 && bytes.get() == 42) reliableEchoes.incrementAndGet();
            }));
            unreliable.onMessage.register(DataChannelCallback.Message.handleBinary((channel, bytes) -> {
                if (bytes.remaining() == 2 && bytes.get() == 0 && bytes.get() == 42) unreliableEchoes.incrementAndGet();
            }));
            String ufrag = "fixture" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            connection.setLocalDescription("offer", ufrag, "p".repeat(32));
            var app = host.application(); var profile = app.getAsJsonObject("profile");
            String keyId = string(profile, "credentialKeyId"), secret = null;
            for (var key : app.getAsJsonArray("keys")) if (keyId.equals(string(key.getAsJsonObject(), "keyId"))) secret = string(key.getAsJsonObject(), "secret");
            if (secret == null) throw new IllegalStateException("Missing installed signing epoch");
            var answer = answer(connection.localDescription(), profile, keyId, secret, ip,
                    ((InetSocketAddress) host.nativeTransport.channel().localAddress()).getPort());
            connection.setRemoteDescription(answer, SessionDescriptionType.ANSWER);
            await(() -> reliable.isOpen() && unreliable.isOpen());
        }
        void exchange() throws Exception {
            int r = reliableEchoes.get() + 1, u = unreliableEchoes.get() + 1;
            reliable.sendMessage(ByteBuffer.allocateDirect(2).put((byte) 0).put((byte) 42).flip());
            unreliable.sendMessage(ByteBuffer.allocateDirect(2).put((byte) 0).put((byte) 42).flip());
            await(() -> reliableEchoes.get() == r && unreliableEchoes.get() == u);
            if (!reliable.isOpen() || !unreliable.isOpen()) throw new IllegalStateException("Native peer retired during continuity phase");
        }
        @Override public void close() { if (!connection.closeAndAwait(Duration.ofSeconds(5))) throw new IllegalStateException("Native peer close timeout"); }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) { if (System.nanoTime() >= end) throw new TimeoutException("Native peer transport exchange timed out"); TimeUnit.MILLISECONDS.sleep(10); }
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(utf8(data));
    }
    private static String field(String sdp, String name) { return sdp.lines().filter(line -> line.startsWith("a=" + name + ":")).findFirst().orElseThrow().substring(name.length() + 3).trim(); }
    private static String answer(String offer, JsonObject profile, String keyId, String secret, String ip, int port) throws Exception {
        String ufrag = field(offer, "ice-ufrag"), pwd = field(offer, "ice-pwd");
        String incarnation = NativeProviderTransport.audience(string(profile.getAsJsonObject("statelessAdmission"), "incarnation"));
        String cpk = "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE7onqrvcQqP/J5uJk+j3M7KhZqAB3OwxxFkg2XodPmV9KmC7ALcVeK0CQ+pJqX88F7mSiEjHsP4o6StG48+3Vvb2BfG8WTuYxmfrYmaS/CZDVHoWibgPWvmkGUbVQG9xq";
        byte[] fp = HexFormat.of().parseHex(field(offer, "fingerprint").substring(8).replace(":", ""));
        byte[] identity = Arrays.copyOf(hmac(utf8(secret), "nxs-identity-binding-v1\0" + incarnation + "\0" + cpk), 16);
        // No game identity proof is performed. Each continuity phase must finish within this normal signed bound.
        ByteBuffer claims = ByteBuffer.allocate(67 + pwd.length());
        claims.putInt((int) ((System.currentTimeMillis() + 55000) / 1000)).put(fp).putShort((short) 5000).putInt(262144).put(identity).putLong(new SecureRandom().nextLong()).put((byte) pwd.length()).put(utf8(pwd));
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hmac(utf8(secret), "nxs-stateless-aead-v1\0" + incarnation), "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(utf8("nxs-stateless-admission-v1\0NXS1" + keyId + "\0" + incarnation + "\0" + ufrag));
        byte[] sealed = cipher.doFinal(claims.array()); Arrays.fill(claims.array(), (byte) 0);
        var base64 = Base64.getEncoder().withoutPadding();
        String token = "NXS1" + keyId + base64.encodeToString(ByteBuffer.allocate(12 + sealed.length).put(nonce).put(sealed).array());
        String password = base64.encodeToString(Arrays.copyOf(hmac(utf8(secret), "nxs-stateless-ice-v1\0" + incarnation + "\0" + token), 24));
        return "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=setup:active\r\n"
                + "a=ice-ufrag:" + token + "\r\na=ice-pwd:" + password + "\r\na=fingerprint:" + string(profile, "dtlsFingerprint")
                + "\r\na=sctp-port:5000\r\na=max-message-size:262144\r\na=candidate:1 1 UDP 2130706431 " + ip + " " + port + " typ host\r\na=end-of-candidates\r\n";
    }
    public static void main(String[] args) {
        MaintainedProviderLocalSmokeClient smoke = null; boolean failed = false;
        try {
            if (args.length != 2 || !args[0].equals("--config")) throw new IllegalArgumentException("Expected --config private-file");
            smoke = new MaintainedProviderLocalSmokeClient(Path.of(args[1])); smoke.run();
        } catch (Throwable failure) { emit("failed", null, null, failureType(failure)); failed = true; }
        finally {
            if (smoke != null) {
                for (var host : smoke.hosts) try { host.stop(); } catch (Throwable failure) { emit("failed", host.id, host.mode, failureType(failure)); failed = true; }
                smoke.reader.shutdownNow(); smoke.group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
                try { Files.deleteIfExists(smoke.trustStore); } catch (Exception ignored) { }
            }
        }
        if (failed) System.exit(1);
    }
    private static JsonObject failureType(Throwable failure) {
        var value = new JsonObject(); value.addProperty("errorType", failure.getClass().getSimpleName());
        while (failure.getCause() != null) failure = failure.getCause();
        value.addProperty("rootErrorType", failure.getClass().getSimpleName()); return value;
    }
    private static synchronized void emit(String phase, String host, String mode, JsonObject fields) {
        var value = fields == null ? new JsonObject() : fields.deepCopy(); value.addProperty("phase", phase);
        if (host != null) value.addProperty("hostId", host); if (mode != null) value.addProperty("transport", mode); System.out.println(value); System.out.flush();
    }
    private static String string(JsonObject value, String name) { return ControlledProviderJson.string(value, name); }
    private static long number(JsonObject value, String name) { return ControlledProviderJson.number(value, name); }
    private static URI uri(JsonObject value, String name) { return URI.create(string(value, name)); }
}
