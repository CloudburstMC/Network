package org.cloudburstmc.netty.signaling.diagnostic;

import org.junit.jupiter.api.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.io.TempDir;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.admission.*;
import org.cloudburstmc.netty.signaling.control.AssistedJoin;
import java.nio.file.Path;
import java.security.KeyPair;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;
import tel.schich.libdatachannel.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native same-mux discovery and proactive diagnostic transport; no public traffic. */
@Tag("native")
class NativeAssistedDiagnosticTest {
    @TempDir Path directory;
    final class Fixture implements AutoCloseable {
        final InetAddress bind; final int hostPort, probePort; final long expiry;
        final Context context = new Context("https://provider.example","assisted-check-host",NativeDiagnosticProbeAttemptTest.id(),1);
        final Key key; final NativeHostIdentity identity; final KeyPair signer = NativeDiagnosticProbeAttemptTest.keyPair();
        final DiagnosticAnswerCodec.Catalog catalog;
        final NativeAdmissionServerChannel host; final DefaultEventLoopGroup group = new DefaultEventLoopGroup(1);
        final NativeDiagnosticHostGate gate; final DiagnosticHostPolicy policy; final NativeDiagnosticProbeAttempt.Job job;
        final AtomicInteger players = new AtomicInteger(); final AtomicBoolean authorized = new AtomicBoolean(true);
        Fixture(String numeric) throws Exception { this(numeric,15000); }
        Fixture(String numeric,long lifetime) throws Exception {
            bind=InetAddress.getByName(numeric); hostPort=NativeDiagnosticProbeAttemptTest.port(bind); probePort=NativeDiagnosticProbeAttemptTest.port(bind);
            expiry=(System.currentTimeMillis()+lifetime)/1000*1000;
            var helper = new NativeDiagnosticProbeAttemptTest(); helper.directory=directory; identity=helper.identity("assisted-"+hostPort);
            key=new Key("D001","test-assistance-"+NativeDiagnosticProbeAttemptTest.id(),0,expiry+10000);
            byte[] encoded=signer.getPublic().getEncoded();
            catalog=new DiagnosticAnswerCodec.Catalog(context.providerOrigin(),0,expiry+10000,List.of(new DiagnosticAnswerCodec.VerificationKey("provider-diagnostic","answer",hex(Arrays.copyOfRange(encoded,encoded.length-97,encoded.length)),0,expiry+10000)));
            var target=DiagnosticHostPolicy.Endpoint.assisted(bind instanceof Inet6Address ? 6 : 4,7);
            policy=new DiagnosticHostPolicy(context,List.of(key),Set.of(target),expiry+10000);
            job=new NativeDiagnosticProbeAttempt.Job(context,NativeDiagnosticProbeAttemptTest.id(),target,identity.fingerprint().substring(8).replace(":","").toLowerCase(Locale.ROOT),expiry);
            host=new NativeAdmissionServerChannel(identity,(request,now)->{players.incrementAndGet();return null;},AdmissionGate.Limits.defaults());
            new ServerBootstrap().group(group).channelFactory(()->host).childHandler(new ChannelInitializer<Channel>() {
                protected void initChannel(Channel channel) { players.incrementAndGet(); }
            }).bind(bind,hostPort).sync();
            gate=host.enableDiagnostics(policy).toCompletableFuture().get(3,TimeUnit.SECONDS);
        }
        JsonObject wire(NativeDiagnosticProbeAttempt.Request request) {
            var credentials=issue(context,key,request.claims(),request.ufrag(),request.offer(),request.assertion(),policy.expiresAt(),Clock.system());
            JsonObject wire=new JsonObject(); wire.addProperty("kind","assisted-join");wire.addProperty("version",1);wire.addProperty("purpose","connectivity-check");
            wire.addProperty("id",job.attemptIdHex());wire.addProperty("instanceId",context.hostId());wire.addProperty("generation",context.generation());
            wire.addProperty("incarnation",context.incarnation());wire.addProperty("keyId",key.keyId());wire.addProperty("hostFingerprint",identity.fingerprint());wire.addProperty("expiresAt",expiry);
            wire.addProperty("localUfrag",credentials.localUfrag());wire.addProperty("localPassword",credentials.icePwd());wire.addProperty("offer",new String(request.offer(),java.nio.charset.StandardCharsets.UTF_8));
            var proof=new JsonObject(); proof.addProperty("publicPointHex",hex(request.assertion().publicPoint()));proof.addProperty("signatureBase64",Base64.getEncoder().encodeToString(request.assertion().signature()));wire.add("assertion",proof);
            return wire;
        }
        AssistedJoin join(NativeDiagnosticProbeAttempt.Request request) { return AssistedJoin.decode(wire(request).toString()); }

        NativeDiagnosticProbeAttempt attempt(InetSocketAddress stun) {
            return new NativeDiagnosticProbeAttempt(job,new InetSocketAddress(bind,probePort),()->catalog,authorized::get,Clock.system(),true,stun);
        }
        CompletionStage<String> respond(NativeDiagnosticProbeAttempt.Request request) {
            assertEquals(ASSISTED_PROFILE,request.claims().profile()); assertEquals(0,request.claims().targetPort());
            assertEquals("00".repeat(16),request.claims().targetAddressHex());
            return host.assistDiagnostic(join(request),()->{if(!authorized.get())throw new IllegalStateException("withdrawn");}).thenApply(answer->{
                assertEquals(1,gate.stats().active()); assertEquals(0,players.get());
                assertEquals(1,answer.lines().filter(line->line.startsWith("a=candidate:")).count());
                return DiagnosticAnswerCodec.sign(new DiagnosticAnswerCodec.Expected(context,request.claims(),request.ufrag(),job.hostFingerprintHex()),utf8(answer),
                        new DiagnosticAnswerCodec.Signer("provider-diagnostic","answer",signer.getPrivate()),()->catalog,DiagnosticAnswerCodec.Options.system());
            });
        }
        public void close() throws Exception { host.close().awaitUninterruptibly();host.termination().toCompletableFuture().get(6,TimeUnit.SECONDS);group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync(); }
    }
    @Test @Timeout(35) void bothFamiliesUseProactiveSignedDiagnosticWithoutHostCandidatesOrPlayerAdmission() throws Exception {
        for(String numeric:List.of("127.0.0.1","::1")) try(var f=new Fixture(numeric);var stun=new StunServer(f.bind);var attempt=f.attempt(stun.address())) {
            var result=attempt.run(f::respond);
            assertTrue(result.success(),result.toString());assertTrue(result.answerVerified());assertTrue(result.transportEstablished());
            assertTrue(result.pingVerified());assertTrue(result.cleanupComplete());
            assertEquals(f.hostPort,result.selectedRemote().getPort());assertEquals(f.probePort,result.selectedLocal().getPort());
            assertEquals(new InetSocketAddress(f.bind,f.probePort),stun.observed.get());
            var reports=new ArrayList<NativeDiagnosticHostGate.Result>();
            NativeDiagnosticProbeAttemptTest.await(()->{reports.addAll(f.gate.pollResults());return !reports.isEmpty();});
            assertEquals(1,reports.size());assertTrue(reports.get(0).success(),reports.toString());
            assertEquals(result.offerDigestHex(),reports.get(0).offerDigestHex());assertEquals(f.job.target(),reports.get(0).target());
            assertEquals(0,f.players.get());assertTrue(f.host.pollEvents().isEmpty());assertEquals(0,f.gate.stats().liveNativePeers());
        }
    }

    @Test @Timeout(20) void privateIpv4HostOfferConnectsThroughLearnedPublicSourceWithoutClientStun() throws Exception {
        // Prefer the interface the kernel actually uses for outbound traffic. Enumerating
        // the first private address can pick an inactive Docker bridge on a CI runner.
        InetAddress privateAddress;
        try (var route = new DatagramSocket()) {
            route.connect(new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 9));
            privateAddress = route.getLocalAddress(); // UDP connect selects a route; it sends no packet.
        }
        if (!(privateAddress instanceof Inet4Address)
                || org.cloudburstmc.netty.util.nethernet.EndpointAddress.scope(privateAddress)
                != org.cloudburstmc.netty.util.nethernet.EndpointAddress.Scope.PRIVATE) {
            privateAddress = NetworkInterface.networkInterfaces().filter(network -> {
                try { return network.isUp() && !network.isLoopback(); }
                catch (SocketException unavailable) { return false; }
            }).flatMap(NetworkInterface::inetAddresses)
                .filter(address -> address instanceof Inet4Address
                        && org.cloudburstmc.netty.util.nethernet.EndpointAddress.scope(address)
                        == org.cloudburstmc.netty.util.nethernet.EndpointAddress.Scope.PRIVATE).findFirst().orElse(null);
        }
        Assumptions.assumeTrue(privateAddress != null, "Requires a private IPv4 interface for the NAT-side bind");
        InetAddress clientAddress = privateAddress;
        int probePort = NativeDiagnosticProbeAttemptTest.port(privateAddress);
        try (var f = new Fixture("127.0.0.1");
             var relay = new PrivateClientRelay(new InetSocketAddress(f.bind, f.hostPort), new InetSocketAddress(privateAddress, probePort));
             var attempt = new NativeDiagnosticProbeAttempt(f.job, new InetSocketAddress(privateAddress, probePort),
                     () -> f.catalog, f.authorized::get, Clock.system(), true)) {
            var result = attempt.run(request -> {
                assertTrue(new String(request.offer(), java.nio.charset.StandardCharsets.UTF_8)
                        .contains(" " + clientAddress.getHostAddress() + " " + probePort + " typ host"));
                return f.host.assistDiagnostic(f.join(request), () -> {}, Map.of(), Map.of(4, relay.address())).thenApply(answer -> {
                    assertFalse(relay.clientSent.get(), "No transport traffic before the signed host answer");
                    return DiagnosticAnswerCodec.sign(new DiagnosticAnswerCodec.Expected(f.context, request.claims(), request.ufrag(), f.job.hostFingerprintHex()),
                            utf8(answer), new DiagnosticAnswerCodec.Signer("provider-diagnostic", "answer", f.signer.getPrivate()),
                            () -> f.catalog, DiagnosticAnswerCodec.Options.system());
                });
            });
            assertTrue(result.success(), () -> result + "; privateBind=" + clientAddress.getHostAddress()
                    + "; clientSent=" + relay.clientSent.get() + "; forwarded=" + relay.forwarded.get()
                    + "; host=" + f.gate.stats() + "; relayFailure=" + relay.failure.get());
            assertTrue(result.pingVerified()); assertTrue(result.cleanupComplete());
            assertEquals(relay.address(), result.attemptedRemote()); assertEquals(relay.address(), result.selectedRemote());
            assertTrue(relay.clientSent.get()); assertTrue(relay.forwarded.get() > 4);
            var reports = new ArrayList<NativeDiagnosticHostGate.Result>();
            NativeDiagnosticProbeAttemptTest.await(() -> { reports.addAll(f.gate.pollResults()); return !reports.isEmpty(); });
            assertTrue(reports.get(0).success(), reports.toString());
            assertEquals(relay.address(), reports.get(0).selectedRemote(), "Host learns the authenticated public source, not the private offer");
            assertEquals(0, f.players.get()); assertEquals(0, f.gate.stats().liveNativePeers());
            System.out.println("private-assisted PASS privateBind=" + clientAddress.getHostAddress()
                    + " forwarded=" + relay.forwarded.get() + " learnedPublicSource=true clientStun=false");
        }
    }

    /** Translates a private client socket to a loopback public-side fixture, without any client STUN discovery. */
    static final class PrivateClientRelay implements AutoCloseable {
        final DatagramSocket socket;
        final AtomicBoolean clientSent = new AtomicBoolean();
        final AtomicInteger forwarded = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        PrivateClientRelay(InetSocketAddress host, InetSocketAddress client) throws Exception {
            socket = new DatagramSocket(new InetSocketAddress(host.getAddress(), 0));
            thread = new Thread(() -> {
                try {
                    while (!socket.isClosed()) {
                        byte[] bytes = new byte[2048]; var packet = new DatagramPacket(bytes, bytes.length); socket.receive(packet);
                        boolean fromClient = client.equals(packet.getSocketAddress());
                        if (!fromClient && !host.equals(packet.getSocketAddress())) throw new IllegalStateException("Unexpected relay source");
                        if (fromClient) clientSent.set(true);
                        else if (!clientSent.get()) throw new IllegalStateException("Host dialled the relay before authenticated inbound ICE");
                        socket.send(new DatagramPacket(bytes, packet.getLength(), fromClient ? host : client)); forwarded.incrementAndGet();
                    }
                } catch (Throwable problem) { if (!socket.isClosed()) failure.set(problem); }
            }, "test-private-client-nat"); thread.setDaemon(true); thread.start();
        }
        InetSocketAddress address() { return (InetSocketAddress) socket.getLocalSocketAddress(); }
        public void close() throws Exception { socket.close(); thread.join(2000); assertFalse(thread.isAlive()); assertNull(failure.get()); }
    }

    @Test @Timeout(20) void assistedTransportTimeoutRetainsVerifiedPeerWithoutInventingSelectedPair() throws Exception {
        for (String numeric : List.of("127.0.0.1", "::1")) try (var f = new Fixture(numeric, 60000);
                var silent = new DatagramSocket(new InetSocketAddress(f.bind, 0))) {
            AtomicLong elapsed = new AtomicLong();
            Clock clock = new Clock(System::currentTimeMillis, () -> System.nanoTime() + elapsed.get());
            var destination = (InetSocketAddress) silent.getLocalSocketAddress();
            try (var attempt = new NativeDiagnosticProbeAttempt(f.job, new InetSocketAddress(f.bind, f.probePort),
                    () -> f.catalog, f.authorized::get, clock, true)) {
                var executor = Executors.newSingleThreadExecutor();
                try {
                    var pending = executor.submit(() -> attempt.run(request -> f.host.assistDiagnostic(f.join(request), () -> {},
                            Map.of(), Map.of(f.job.target().family(), destination)).thenApplyAsync(answer -> {
                            // Retire the proactive host peer so its ICE cannot race the silent destination.
                            f.host.close().awaitUninterruptibly();
                            try { f.host.termination().toCompletableFuture().get(3, TimeUnit.SECONDS); }
                            catch (Exception failure) { throw new CompletionException(failure); }
                            return DiagnosticAnswerCodec.sign(new DiagnosticAnswerCodec.Expected(f.context, request.claims(), request.ufrag(), f.job.hostFingerprintHex()),
                                    utf8(answer), new DiagnosticAnswerCodec.Signer("provider-diagnostic", "answer", f.signer.getPrivate()),
                                    () -> f.catalog, DiagnosticAnswerCodec.Options.system());
                            })));
                    silent.setSoTimeout(3000); silent.receive(new DatagramPacket(new byte[2048], 2048));
                    elapsed.set(TimeUnit.SECONDS.toNanos(16));
                    var result = pending.get(3, TimeUnit.SECONDS);
                    assertFalse(result.success()); assertEquals(NativeDiagnosticProbeAttempt.Reason.TRANSPORT, result.reason());
                    assertTrue(result.answerVerified()); assertFalse(result.transportEstablished()); assertTrue(result.cleanupComplete());
                    assertEquals(destination, result.attemptedRemote()); assertNull(result.selectedLocal()); assertNull(result.selectedRemote());
                } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
            }
        }
    }

    @Test @Timeout(20) void signedPrivateServerReflexiveAndNonIpv4HostOffersCreateNoPeer() throws Exception {
        for (String[] candidate : List.of(new String[]{"127.0.0.1", "10.0.0.1", "srflx"},
                new String[]{"::1", "fc00::1", "host"}, new String[]{"127.0.0.1", "169.254.169.254", "host"})) {
            try (var f = new Fixture(candidate[0]); var attempt = f.attempt(null)) {
                var proofKey = NativeDiagnosticProbeAttemptTest.keyPair();
                var result = attempt.run(original -> {
                    String sdp = new String(original.offer(), java.nio.charset.StandardCharsets.UTF_8);
                    sdp = sdp.replaceAll("(?m)(^a=candidate:[^\\r\\n]*? UDP [0-9]+ )[^ ]+", "$1" + candidate[1])
                            .replace("typ host", "typ " + candidate[2]);
                    var c = original.claims();
                    var claims = new Claims(c.expiresAt(), c.clientFingerprintHex(), c.clientIcePwd(), c.attemptIdHex(),
                            hex(digest(utf8(sdp))), c.candidateRevision(), c.family(), c.targetAddressHex(), c.targetPort(), c.profile());
                    var proof = DiagnosticAssertionCodec.sign(f.context, claims, original.ufrag(), proofKey);
                    var request = new NativeDiagnosticProbeAttempt.Request(f.context, claims, original.ufrag(), utf8(sdp), proof);
                    return f.host.assistDiagnostic(f.join(request), () -> {}).handle((answer, failure) -> {
                        assertNotNull(failure, Arrays.toString(candidate));
                        assertEquals(0, f.gate.stats().active()); assertEquals(0, f.gate.stats().liveNativePeers());
                        return "rejected before native peer creation";
                    });
                });
                assertFalse(result.success()); assertFalse(result.answerVerified()); assertNull(result.attemptedRemote());
                assertTrue(result.cleanupComplete()); assertEquals(0, f.players.get());
                assertTrue(f.gate.stats().rejected() > 0); assertEquals(0, f.gate.stats().active()); assertEquals(0, f.gate.stats().liveNativePeers());
            }
        }
    }

    @Test @Timeout(35) void changedBindingProofExpiryAndWithdrawalCreateNoDiagnosticOrPlayerPeer() throws Exception {
        for(String mode:List.of("generation","fingerprint","expiry","offer","assertion","withdraw","player-purpose"))
            try(var f=new Fixture("127.0.0.1");var attempt=f.attempt(null)) {
                var result=attempt.run(request->{
                    var wire=f.wire(request);
                    switch(mode) {
                        case "generation" -> wire.addProperty("generation",2);
                        case "fingerprint" -> wire.addProperty("hostFingerprint","sha-256 "+String.join(":",Collections.nCopies(32,"AA")));
                        case "expiry" -> wire.addProperty("expiresAt",System.currentTimeMillis()-1);
                        case "offer" -> wire.addProperty("offer",wire.get("offer").getAsString().replace("typ host","typ srflx"));
                        case "assertion" -> wire.getAsJsonObject("assertion").addProperty("signatureBase64",Base64.getEncoder().encodeToString(new byte[96]));
                        case "withdraw" -> f.gate.replacePolicy(new DiagnosticHostPolicy(f.context,List.of(f.key),Set.of(),f.expiry+10000));
                        case "player-purpose" -> wire.addProperty("cpk","untrusted");
                    }
                    try {
                        var join=AssistedJoin.decode(wire.toString());
                        return f.host.assistDiagnostic(join,()->{});
                    } catch (RuntimeException invalid) { return CompletableFuture.failedFuture(invalid); }
                });
                assertFalse(result.success(),mode);assertFalse(result.answerVerified(),mode);assertTrue(result.cleanupComplete(),mode);
                assertEquals(0,f.gate.stats().active(),mode);
                assertEquals(0,f.gate.stats().liveNativePeers(),mode);assertEquals(0,f.players.get(),mode);assertTrue(f.host.pollEvents().isEmpty(),mode);
            }
    }

    @Test @Timeout(35) void assistedPeerLearnsAuthenticatedSourcePortWithoutReplyingToForgedChecks() throws Exception {
        for (String numeric : List.of("127.0.0.1", "::1")) try (var f = new Fixture(numeric);
                var advertised = new DatagramSocket(new InetSocketAddress(f.bind, 0));
                var forged = new DatagramSocket(new InetSocketAddress(f.bind, 0));
                var stun = new StunServer(f.bind, source -> new InetSocketAddress(f.bind, advertised.getLocalPort()));
                var attempt = f.attempt(stun.address())) {
            var result = attempt.run(request -> {
                var join = f.join(request);
                assertTrue(join.offer().contains(" " + advertised.getLocalPort() + " typ srflx"));
                return f.host.assistDiagnostic(join, () -> {}).thenApplyAsync(answer -> {
                    try {
                        byte[] packet = binding(join.localUfrag() + ":" + request.ufrag(), "wrong-password");
                        forged.send(new DatagramPacket(packet, packet.length, f.bind, f.hostPort));
                        forged.setSoTimeout(250);
                        assertThrows(SocketTimeoutException.class,
                                () -> forged.receive(new DatagramPacket(new byte[2048], 2048)),
                                "An unauthenticated source must not receive an ICE response");
                    } catch (Exception failure) { throw new CompletionException(failure); }
                    return DiagnosticAnswerCodec.sign(new DiagnosticAnswerCodec.Expected(f.context, request.claims(), request.ufrag(), f.job.hostFingerprintHex()), utf8(answer),
                            new DiagnosticAnswerCodec.Signer("provider-diagnostic", "answer", f.signer.getPrivate()), () -> f.catalog, DiagnosticAnswerCodec.Options.system());
                });
            });
            assertTrue(result.success(), result.toString());
            assertTrue(result.transportEstablished()); assertTrue(result.pingVerified()); assertTrue(result.cleanupComplete());
            assertEquals(new InetSocketAddress(f.bind, f.probePort), stun.observed.get(), "STUN uses the actual peer socket");
            assertEquals(f.hostPort, result.selectedRemote().getPort());
            var reports = new ArrayList<NativeDiagnosticHostGate.Result>();
            NativeDiagnosticProbeAttemptTest.await(() -> { reports.addAll(f.gate.pollResults()); return !reports.isEmpty(); });
            assertEquals(1, reports.size());
            var report = reports.get(0);
            assertTrue(report.success(), report.toString());
            assertEquals(new InetSocketAddress(f.bind, f.probePort), report.selectedRemote());
            assertNotEquals(advertised.getLocalPort(), report.selectedRemote().getPort());
            assertEquals(0, f.players.get()); assertTrue(f.host.pollEvents().isEmpty());
        }
    }

    @Test @Timeout(20) void validProfileTwoStunCannotCreateAnInboundDiagnosticPeer() throws Exception {
        try(var f=new Fixture("127.0.0.1");var attempt=f.attempt(null)) {
            var result=attempt.run(request->{
                try {
                    var join=f.join(request);
                    byte[] packet=binding(join.localUfrag()+":"+request.ufrag(),join.localPassword());
                    try(var socket=new DatagramSocket(new InetSocketAddress(f.bind,0))) {
                        socket.send(new DatagramPacket(packet,packet.length,f.bind,f.hostPort));
                        NativeDiagnosticProbeAttemptTest.await(()->f.gate.stats().rejected()>0);
                    }
                    assertEquals(0,f.gate.stats().active());assertEquals(0,f.gate.stats().retainedAttempts());assertEquals(0,f.players.get());
                    return f.respond(request); // Rejected inbound traffic did not consume proactive authority.
                } catch(Exception failure) {return CompletableFuture.failedFuture(failure);}
            });
            assertTrue(result.success(),result.toString());assertEquals(0,f.players.get());
        }
    }
    static byte[] binding(String username,String password) throws Exception {
        byte[] name=username.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int offset=24+((name.length+3)&~3);var packet=ByteBuffer.allocate(offset+24);
        packet.putShort((short)1).putShort((short)(packet.capacity()-20)).putInt(0x2112a442).put(new byte[12]);
        packet.putShort((short)6).putShort((short)name.length).put(name);packet.position(offset);packet.putShort((short)8).putShort((short)20);
        var mac=javax.crypto.Mac.getInstance("HmacSHA1");mac.init(new javax.crypto.spec.SecretKeySpec(password.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA1"));
        packet.put(mac.doFinal(Arrays.copyOf(packet.array(),offset)));return packet.array();
    }

    static final class StunServer implements AutoCloseable {
        final DatagramSocket socket;
        final AtomicReference<InetSocketAddress> observed = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        StunServer(InetAddress bind) throws Exception { this(bind, source -> source); }
        StunServer(InetAddress bind, java.util.function.UnaryOperator<InetSocketAddress> mapped) throws Exception {
            socket = new DatagramSocket(new InetSocketAddress(bind, 0));
            thread = new Thread(() -> {
                try {
                    while (!socket.isClosed()) {
                        byte[] data = new byte[2048];
                        var request = new DatagramPacket(data, data.length); socket.receive(request);
                        if (request.getLength() < 20 || data[0] != 0 || data[1] != 1) continue;
                        var source = new InetSocketAddress(request.getAddress(), request.getPort()); observed.set(source);
                        var external = mapped.apply(source);
                        byte[] address = external.getAddress().getAddress();
                        var response = ByteBuffer.allocate(28 + address.length);
                        response.putShort((short)0x0101).putShort((short)(8 + address.length)).putInt(0x2112a442).put(data,8,12);
                        response.putShort((short)0x0020).putShort((short)(4 + address.length));
                        response.put((byte)0).put((byte)(address.length == 4 ? 1 : 2)).putShort((short)(external.getPort() ^ 0x2112));
                        byte[] mask = Arrays.copyOfRange(response.array(), 4, 20);
                        for (int i=0;i<address.length;i++) response.put((byte)(address[i] ^ mask[i]));
                        socket.send(new DatagramPacket(response.array(),response.position(),source));
                    }
                } catch (Throwable error) { if (!socket.isClosed()) failure.set(error); }
            }, "test-stun"); thread.setDaemon(true); thread.start();
        }
        InetSocketAddress address() { return new InetSocketAddress(socket.getLocalAddress(),socket.getLocalPort()); }
        public void close() throws Exception { socket.close(); thread.join(2000); assertFalse(thread.isAlive()); assertNull(failure.get()); }
    }

    /** Real UDP translation with address/port filtering: each side must first send to the other's external tuple. */
    static final class RestrictivePair implements AutoCloseable {
        final DatagramSocket hostExternal, probeExternal;
        final InetSocketAddress hostInternal, probeInternal;
        final AtomicBoolean hostSent = new AtomicBoolean(), probeSent = new AtomicBoolean();
        final AtomicInteger droppedHost = new AtomicInteger(), forwarded = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread hostThread, probeThread;
        RestrictivePair(InetAddress bind, int hostPort, int probePort) throws Exception {
            hostInternal = new InetSocketAddress(bind,hostPort); probeInternal = new InetSocketAddress(bind,probePort);
            hostExternal = new DatagramSocket(new InetSocketAddress(bind,0)); probeExternal = new DatagramSocket(new InetSocketAddress(bind,0));
            hostThread = relay(hostExternal,probeInternal,probeSent,hostSent,probeExternal,hostInternal,false);
            probeThread = relay(probeExternal,hostInternal,hostSent,probeSent,hostExternal,probeInternal,true);
        }
        InetSocketAddress mapped(InetSocketAddress source) {
            if (source.equals(hostInternal)) return (InetSocketAddress)hostExternal.getLocalSocketAddress();
            if (source.equals(probeInternal)) return (InetSocketAddress)probeExternal.getLocalSocketAddress();
            throw new IllegalArgumentException("Unexpected fixture source");
        }
        Thread relay(DatagramSocket inbound, InetSocketAddress expected, AtomicBoolean senderOpened,
                     AtomicBoolean receiverOpened, DatagramSocket outbound, InetSocketAddress destination, boolean fromHost) {
            Thread thread = new Thread(() -> {
                try { while (!inbound.isClosed()) {
                    byte[] bytes = new byte[2048]; var packet = new DatagramPacket(bytes,bytes.length); inbound.receive(packet);
                    if (!expected.equals(packet.getSocketAddress())) throw new IllegalStateException("Unexpected translated source");
                    senderOpened.set(true);
                    if (!receiverOpened.get()) { if (fromHost) droppedHost.incrementAndGet(); continue; }
                    outbound.send(new DatagramPacket(bytes,packet.getLength(),destination)); forwarded.incrementAndGet();
                } } catch (Throwable problem) { if (!inbound.isClosed()) failure.compareAndSet(null,problem); }
            }, "test-restrictive-nat"); thread.setDaemon(true);thread.start();return thread;
        }
        public void close() throws Exception {
            hostExternal.close();probeExternal.close();hostThread.join(2000);probeThread.join(2000);
            assertFalse(hostThread.isAlive());assertFalse(probeThread.isAlive());assertNull(failure.get());
        }
    }

    @Test @Timeout(40) void bothRestrictedFiltersRequireFreshReciprocalCandidateOnTheSameMux() throws Exception {
        for (String numeric : List.of("127.0.0.1","::1")) try (var f=new Fixture(numeric);
                var nat=new RestrictivePair(f.bind,f.hostPort,f.probePort);
                var stun=new StunServer(f.bind, source -> {
                    if (source.getPort()==f.hostPort) {
                        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                        while(nat.droppedHost.get()==0 && System.nanoTime()<end) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    }
                    return nat.mapped(source);
                }); var attempt=f.attempt(stun.address())) {
            var result=attempt.run(request -> f.host.assistDiagnostic(f.join(request),()->{if(!f.authorized.get())throw new IllegalStateException("withdrawn");},
                    Map.of(f.job.target().family(),stun.address()),Map.of()).thenApply(answer -> {
                assertTrue(nat.droppedHost.get()>0,"host ICE was filtered before probe contacted its fresh public tuple");
                assertFalse(nat.probeSent.get(),"probe sent no transport UDP before signed answer verification");
                assertTrue(answer.contains(" " + nat.hostExternal.getLocalPort() + " typ srflx"));
                return DiagnosticAnswerCodec.sign(new DiagnosticAnswerCodec.Expected(f.context,request.claims(),request.ufrag(),f.job.hostFingerprintHex()),utf8(answer),
                        new DiagnosticAnswerCodec.Signer("provider-diagnostic","answer",f.signer.getPrivate()),()->f.catalog,DiagnosticAnswerCodec.Options.system());
            }));
            assertTrue(result.success(),result.toString());assertTrue(result.pingVerified());assertTrue(result.cleanupComplete());
            assertTrue(nat.hostSent.get());assertTrue(nat.probeSent.get());assertTrue(nat.forwarded.get()>4);
            assertEquals(nat.hostExternal.getLocalPort(),result.selectedRemote().getPort());assertEquals(0,f.players.get());
            var reports=new ArrayList<NativeDiagnosticHostGate.Result>();
            NativeDiagnosticProbeAttemptTest.await(()->{reports.addAll(f.gate.pollResults());return !reports.isEmpty();});
            assertTrue(reports.get(0).success(),reports.toString());assertEquals(0,f.gate.stats().liveNativePeers());
        }
    }

    @Test @Timeout(35) void forwardedHostAcceptsProbeMappingThatChangesAfterStunDiscovery() throws Exception {
        for (String numeric : List.of("127.0.0.1", "::1")) try (var f = new Fixture(numeric);
                var nat = new RestrictivePair(f.bind, f.hostPort, f.probePort);
                var advertised = new DatagramSocket(new InetSocketAddress(f.bind, 0));
                var stun = new StunServer(f.bind, source -> source.getPort() == f.probePort
                        ? (InetSocketAddress) advertised.getLocalSocketAddress() : nat.mapped(source));
                var attempt = f.attempt(stun.address())) {
            nat.hostSent.set(true); // The host has a port forward; the probe's NAT still filters inbound traffic.
            var result = attempt.run(request -> f.host.assistDiagnostic(f.join(request), () -> {},
                    Map.of(f.job.target().family(), stun.address()), Map.of()).thenApply(answer ->
                    DiagnosticAnswerCodec.sign(new DiagnosticAnswerCodec.Expected(f.context, request.claims(), request.ufrag(), f.job.hostFingerprintHex()), utf8(answer),
                            new DiagnosticAnswerCodec.Signer("provider-diagnostic", "answer", f.signer.getPrivate()), () -> f.catalog, DiagnosticAnswerCodec.Options.system())));
            assertTrue(result.success(), result.toString());
            assertTrue(result.transportEstablished());  assertTrue(result.pingVerified()); assertTrue(result.cleanupComplete());
            assertEquals(nat.probeExternal.getLocalSocketAddress(), result.selectedLocal());
            assertNotEquals(advertised.getLocalPort(), result.selectedLocal().getPort());
            assertNotEquals(f.probePort, result.selectedLocal().getPort());
            assertEquals(nat.hostExternal.getLocalSocketAddress(), result.selectedRemote());
            var reports = new ArrayList<NativeDiagnosticHostGate.Result>();
            NativeDiagnosticProbeAttemptTest.await(() -> { reports.addAll(f.gate.pollResults()); return !reports.isEmpty(); });
            assertEquals(1, reports.size()); assertTrue(reports.get(0).success(), reports.toString());
            assertEquals(nat.probeExternal.getLocalSocketAddress(), reports.get(0).selectedRemote());
            assertEquals(0, f.players.get()); assertEquals(0, f.gate.stats().liveNativePeers());
        }
    }

    @Test @Timeout(20) void pendingHostDiscoveryClosesOnWithdrawalExpiryAndOwnerClose() throws Exception {
        for (String mode : List.of("withdraw","expiry","close")) {
            int hostPort,probePort;
            try (var f=new Fixture("127.0.0.1",mode.equals("expiry") ? 2500 : 15000);
                 var silent=new DatagramSocket(new InetSocketAddress(f.bind,0)); var attempt=f.attempt(null)) {
                hostPort=f.hostPort;probePort=f.probePort; silent.setSoTimeout(2000);
                var answer=new AtomicReference<CompletableFuture<String>>();
                var result=attempt.run(request -> {
                    var pending=f.host.assistDiagnostic(f.join(request),()->{if(!f.authorized.get())throw new IllegalStateException("withdrawn");},
                            Map.of(4,(InetSocketAddress)silent.getLocalSocketAddress()),Map.of()).toCompletableFuture();
                    answer.set(pending);
                    try {
                        var packet=new DatagramPacket(new byte[2048],2048);silent.receive(packet);
                        assertEquals(f.hostPort,packet.getPort(),"per-attempt discovery owns the gameplay source port");
                        if(mode.equals("withdraw"))f.authorized.set(false);
                        if(mode.equals("close"))f.host.close();
                    } catch(Exception failure) { return CompletableFuture.failedFuture(failure); }
                    return pending;
                });
                assertFalse(result.success(),mode);assertFalse(result.answerVerified(),mode);assertTrue(result.cleanupComplete(),mode);
                NativeDiagnosticProbeAttemptTest.await(()->answer.get().isCompletedExceptionally()&&f.gate.stats().liveNativePeers()==0);
                assertEquals(0,f.players.get());
            }
            try(var hostReuse=new DatagramSocket(new InetSocketAddress("127.0.0.1",hostPort));
                var probeReuse=new DatagramSocket(new InetSocketAddress("127.0.0.1",probePort))) {
                assertEquals(hostPort,hostReuse.getLocalPort());assertEquals(probePort,probeReuse.getLocalPort());
            }
        }
    }

    @Test @Timeout(20) void discoverySharesPeerSocket() throws Exception {
        for (String numeric : List.of("127.0.0.1", "::1")) {
            InetAddress bind = InetAddress.getByName(numeric);
            int port = NativeDiagnosticProbeAttemptTest.port(bind);
            try (var server = new StunServer(bind);
                 var monitor = new StunUdpMuxMonitor(bind,port,server.address().getHostString(),server.address().getPort());
                 var peer = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withBindAddress(bind)
                         .withPortRangeBegin(port).withPortRangeEnd(port).withEnableIceUdpMux(true).withIceServers(List.of())
                         .withDisableAutoNegotiation(true), Runnable::run)) {
                peer.createDataChannel("test"); peer.setLocalDescription("offer","probeFixture","p".repeat(24));
                NativeDiagnosticProbeAttemptTest.await(() -> monitor.binding(0).map(b -> b.state()==StunBinding.State.SUCCEEDED).orElse(false));
                assertEquals(new InetSocketAddress(bind,port),server.observed.get());
                var mapping = monitor.binding(0).orElseThrow(); assertEquals(port,mapping.mappedPort());
                assertEquals(bind,InetAddress.getByName(mapping.mappedAddress()));
                assertTrue(peer.localDescription().contains(" " + port + " typ host"));
                assertTrue(peer.closeAndAwait(Duration.ofSeconds(5)));
            }
        }
    }
}
