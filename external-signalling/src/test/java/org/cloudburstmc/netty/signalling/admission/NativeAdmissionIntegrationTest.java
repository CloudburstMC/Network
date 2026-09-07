package org.cloudburstmc.netty.signalling.admission;

import org.cloudburstmc.netty.channel.nethernet.admission.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakConstants;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tel.schich.libdatachannel.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Tag("native")
class NativeAdmissionIntegrationTest {
    @Test @Timeout(30) void dualStackWildcardAcceptsBothFamiliesAndRetainsSingleTicketOwnership() throws Exception {
        var id = identity(); var group = new DefaultEventLoopGroup(1);
        int port = 49188;
        var v4 = new InetSocketAddress("127.0.0.1", port);
        var v6 = new InetSocketAddress("::1", port);
        var advertised = new AtomicReference<>(List.of(v6, v4, v4));
        NativeProviderTransport host = null;
        try {
            var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {}
            });
            host = NativeProviderTransport.open(bootstrap, new InetSocketAddress("::", port), advertised::get,
                id.certificate(), id.privateKey(), AdmissionGate.Limits.defaults()).toCompletableFuture().get(10, TimeUnit.SECONDS);
            host.installTicketKeys(List.of(new org.cloudburstmc.netty.signalling.ProviderTransport.TicketKey("K001", TestSignallingProvider.SECRET))).toCompletableFuture().get();
            var profile = host.hostProfile().toCompletableFuture().get();
            assertEquals(2, profile.getAsJsonArray("candidates").size());
            String incarnation = profile.getAsJsonObject("statelessAdmission").get("incarnation").getAsString();
            String audience = NativeProviderTransport.audience(incarnation);
            var endpoint = host.channel();
            for (var destination : List.of(v4, v6)) {
                var other = destination.equals(v4) ? v6 : v4;
                try (var socket = new DatagramSocket(new InetSocketAddress(destination.getAddress(), 0));
                     var duplicate = new DatagramSocket(new InetSocketAddress(other.getAddress(), 0));
                     var client = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true).withBindAddress(destination.getAddress()), Runnable::run)) {
                    client.createDataChannel("ReliableDataChannel");
                    String ufrag = destination.equals(v4) ? "dualStackClient4" : "dualStackClient6";
                    client.setLocalDescription("offer", ufrag, "p".repeat(32));
                    var answer = TestSignallingProvider.answer(client.localDescription(), id.fingerprint(), port,
                        System.currentTimeMillis() + 30_000, audience, false);
                    byte[] request = nominatedBinding(answer.token() + ":" + ufrag, answer.password());
                    socket.setSoTimeout(2000);
                    socket.send(new DatagramPacket(request, request.length, destination));
                    byte[] bytes = new byte[2048]; var response = new DatagramPacket(bytes, bytes.length);
                    socket.receive(response);
                    assertEquals(destination.getAddress(), response.getAddress()); assertEquals(port, response.getPort());
                    assertEquals(0x0101, Short.toUnsignedInt(ByteBuffer.wrap(bytes).getShort()));
                    assertArrayEquals(Arrays.copyOfRange(request, 8, 20), Arrays.copyOfRange(bytes, 8, 20));
                    long creations = endpoint.creationAttempts();
                    duplicate.setSoTimeout(250);
                    duplicate.send(new DatagramPacket(request, request.length, other));
                    assertThrows(SocketTimeoutException.class, () -> duplicate.receive(new DatagramPacket(new byte[2048], 2048)));
                    assertEquals(creations, endpoint.creationAttempts(), "An alternate family cannot allocate a second peer with the same ticket");
                }
            }
            assertEquals(2, endpoint.creationAttempts());
            advertised.set(List.of(v4));
            var changed = host.hostProfile().toCompletableFuture().get();
            assertEquals(1, changed.getAsJsonArray("candidates").size());
            assertEquals(incarnation, changed.getAsJsonObject("statelessAdmission").get("incarnation").getAsString());
            advertised.set(List.of());
            assertTrue(host.hostProfile().toCompletableFuture().isCompletedExceptionally());
        } finally {
            if (host != null) host.close().toCompletableFuture().get(10, TimeUnit.SECONDS);
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
        try (var socket = new DatagramSocket(new InetSocketAddress("::", port))) { assertEquals(port, socket.getLocalPort()); }
    }

    @Test @Timeout(40) void clientsOfEitherFamilyOpenBothDataChannelsFromTheSameCandidateList() throws Exception {
        var id = identity(); var group = new DefaultEventLoopGroup(1); int port = 49187;
        NativeProviderTransport host = null;
        try {
            var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {}
            });
            host = NativeProviderTransport.open(bootstrap, new InetSocketAddress("::", port),
                () -> List.of(new InetSocketAddress("::1", port), new InetSocketAddress("127.0.0.1", port)),
                id.certificate(), id.privateKey(), AdmissionGate.Limits.defaults()).toCompletableFuture().get(10, TimeUnit.SECONDS);
            host.installTicketKeys(List.of(new org.cloudburstmc.netty.signalling.ProviderTransport.TicketKey("K001", TestSignallingProvider.SECRET))).toCompletableFuture().get();
            String incarnation = host.hostProfile().toCompletableFuture().get().getAsJsonObject("statelessAdmission").get("incarnation").getAsString();
            for (String ip : List.of("127.0.0.1", "::1")) {
                try (var client = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true).withBindAddress(InetAddress.getByName(ip)), Runnable::run)) {
                    AtomicInteger opened = new AtomicInteger();
                    client.createDataChannel("ReliableDataChannel").onOpen.register(dc -> opened.incrementAndGet());
                    client.createDataChannel("UnreliableDataChannel", DataChannelInitSettings.DEFAULT.withReliability(new DataChannelReliability(true, true, 0, 0)))
                        .onOpen.register(dc -> opened.incrementAndGet());
                    client.setLocalDescription("offer", ip.equals("::1") ? "clientWithIpv6" : "clientWithIpv4", "p".repeat(32));
                    var answer = TestSignallingProvider.answer(client.localDescription(), id.fingerprint(), port,
                        System.currentTimeMillis() + 30_000, NativeProviderTransport.audience(incarnation), false);
                    String sdp = answer.sdp().replace("a=candidate:1 1 UDP 2130706431 127.0.0.1", "a=candidate:2 1 UDP 2130706175 127.0.0.1")
                        .replace("a=end-of-candidates", "a=candidate:1 1 UDP 2130706431 ::1 " + port + " typ host\r\na=end-of-candidates");
                    client.setRemoteDescription(sdp, SessionDescriptionType.ANSWER);
                    await(() -> opened.get() == 2);
                    assertTrue(client.closeAndAwait(Duration.ofSeconds(5)));
                }
            }
            assertEquals(2, host.channel().creationAttempts());
        } finally {
            if (host != null) host.close().toCompletableFuture().get(10, TimeUnit.SECONDS);
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @TempDir Path directory;
    NativeHostIdentity identity() throws Exception {
        Path cert = directory.resolve("host.crt"), key = directory.resolve("host.key");
        Process p = new ProcessBuilder("openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes", "-keyout", key.toString(), "-out", cert.toString(), "-days", "1", "-subj", "/CN=public-test-only").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));assertEquals(0,p.exitValue());
        return NativeHostIdentity.load(cert,key);
    }
    static void await(BooleanSupplier check) throws Exception {
        long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while (!check.getAsBoolean() && System.nanoTime()<end) Thread.sleep(10);
        assertTrue(check.getAsBoolean());
    }
    static void rakPing(int port) throws Exception {
        try(var socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            byte[] ping=ByteBuffer.allocate(33).put((byte)1).putLong(12345).put(RakConstants.DEFAULT_UNCONNECTED_MAGIC).putLong(42).array();
            socket.send(new DatagramPacket(ping,ping.length,InetAddress.getByName("127.0.0.1"),port));
            byte[] reply=new byte[2048];var packet=new DatagramPacket(reply,reply.length);socket.receive(packet);
            assertEquals(port,packet.getPort());assertEquals(0x1c,reply[0]);assertEquals(12345,ByteBuffer.wrap(reply).getLong(1));
        }
    }
    @Test @Timeout(20) void wildcardBindPublishesExplicitCandidateAndAcceptsIngress() throws Exception {
        var id = identity(); var group = new DefaultEventLoopGroup(1);
        var bind = new InetSocketAddress("0.0.0.0", 49189);
        var advertised = new InetSocketAddress("127.0.0.1", 49189);
        NativeProviderTransport host = null;
        try {
            var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInboundHandlerAdapter());
            assertThrows(ExecutionException.class, () -> NativeProviderTransport.open(bootstrap, bind, bind,
                id.certificate(), id.privateKey(), AdmissionGate.Limits.defaults()).toCompletableFuture().get());
            host = NativeProviderTransport.open(bootstrap, bind, advertised, id.certificate(), id.privateKey(),
                AdmissionGate.Limits.defaults()).toCompletableFuture().get(10, TimeUnit.SECONDS);
            host.installTicketKeys(List.of(new org.cloudburstmc.netty.signalling.ProviderTransport.TicketKey(
                "K001", TestSignallingProvider.SECRET, 0, Long.MAX_VALUE))).toCompletableFuture().get();
            var profile = host.hostProfile().toCompletableFuture().get();
            var candidate = profile.getAsJsonArray("candidates").get(0).getAsJsonObject();
            assertEquals("127.0.0.1", candidate.get("address").getAsString());
            assertEquals(49189, candidate.get("port").getAsInt());
            assertEquals("nethernet.stateless-admission.v1", profile.getAsJsonObject("statelessAdmission").get("capability").getAsString());
            try (var socket = new DatagramSocket()) {
                byte[] packet = new byte[40]; socket.send(new DatagramPacket(packet, packet.length, advertised));
            }
            var endpoint = host.channel(); await(() -> endpoint.nativeStats()[0] > 0);
            assertEquals(0, endpoint.nativeStats()[5], "Malformed UDP stays native");
            assertEquals(0, endpoint.creationAttempts());
        } finally {
            if (host != null) host.close().toCompletableFuture().get(10, TimeUnit.SECONDS);
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
        try (var socket = new DatagramSocket(bind)) { assertEquals(49189, socket.getLocalPort()); }
    }
    @Test @Timeout(20) void firstAuthenticatedDatagramGetsMatchingResponseWithoutRetry() throws Exception {
        var id = identity(); var loopback = InetAddress.getByName("127.0.0.1"); int port = 49199;
        var validator = new StatelessAdmissionValidator(TestSignallingProvider.AUDIENCE, 60_000);
        validator.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", TestSignallingProvider.SECRET)));
        var group = new DefaultEventLoopGroup(1);
        AtomicInteger validations = new AtomicInteger();
        AdmissionValidator delayed = (metadata, now) -> {
            validations.incrementAndGet();
            try { Thread.sleep(200); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
            return validator.validate(metadata, now);
        };
        var endpoint = new NativeAdmissionServerChannel(id, delayed, new AdmissionGate.Limits(2, 4, 1, 10_000));
        try (var socket = new DatagramSocket(new InetSocketAddress(loopback, 0));
             var client = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true).withBindAddress(loopback), Runnable::run)) {
            new ServerBootstrap().group(group).channelFactory(() -> endpoint)
                .childHandler(new ChannelInboundHandlerAdapter()).bind(loopback, port).sync();
            client.createDataChannel("ReliableDataChannel");
            client.setLocalDescription("offer", "singleCheckClient", "p".repeat(32));
            var answer = TestSignallingProvider.answer(client.localDescription(), id.fingerprint(), port,
                System.currentTimeMillis() + 30_000, TestSignallingProvider.AUDIENCE, false);
            // Never apply the answer to the native client: only this socket sends one check.
            byte[] request = nominatedBinding(answer.token() + ":singleCheckClient", answer.password());
            socket.setSoTimeout(2000);
            long started = System.nanoTime();
            socket.send(new DatagramPacket(request, request.length, loopback, port));
            byte[] bytes = new byte[2048]; var response = new DatagramPacket(bytes, bytes.length);
            socket.receive(response);
            double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
            assertEquals(loopback, response.getAddress()); assertEquals(port, response.getPort());
            assertEquals(0x0101, Short.toUnsignedInt(ByteBuffer.wrap(bytes).getShort()));
            assertArrayEquals(Arrays.copyOfRange(request, 8, 20), Arrays.copyOfRange(bytes, 8, 20));
            assertEquals(1, endpoint.creationAttempts()); assertEquals(1, endpoint.nativeStats()[3]);
            assertEquals(1, validations.get()); assertEquals(1, endpoint.nativeStats()[5]);
            // Authenticated retransmissions on the established tuple never return to admission.
            for (int i = 0; i < 40; i++) socket.send(new DatagramPacket(request, request.length, loopback, port));
            await(() -> endpoint.nativeStats()[0] >= 41);
            assertEquals(1, validations.get()); assertEquals(1, endpoint.nativeStats()[5]);
            System.out.printf(Locale.ROOT, "first-stun PASS requestsSent=1 matchingSuccess=true responseMs=%.3f%n", elapsedMs);
        } finally {
            if (endpoint.isRegistered()) endpoint.close().awaitUninterruptibly(); else endpoint.unsafe().closeForcibly();
            endpoint.termination().toCompletableFuture().get(6, TimeUnit.SECONDS);
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
    @Test @Timeout(20) void expiredDecisionCreatesNoPeerAndReleasesItsReservation() throws Exception {
        var id = identity(); var loopback = InetAddress.getByName("127.0.0.1"); int port = 49200;
        var validator = new StatelessAdmissionValidator(TestSignallingProvider.AUDIENCE, 60_000);
        validator.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", TestSignallingProvider.SECRET)));
        AdmissionValidator delayed = (metadata, now) -> {
            var admission = validator.validate(metadata, now);
            try { Thread.sleep(2500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
            return admission;
        };
        var group = new DefaultEventLoopGroup(1);
        var endpoint = new NativeAdmissionServerChannel(id, delayed, new AdmissionGate.Limits(2, 4, 1, 10_000));
        try (var socket = new DatagramSocket(new InetSocketAddress(loopback, 0));
             var client = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true).withBindAddress(loopback), Runnable::run)) {
            new ServerBootstrap().group(group).channelFactory(() -> endpoint)
                .childHandler(new ChannelInboundHandlerAdapter()).bind(loopback, port).sync();
            client.createDataChannel("ReliableDataChannel");
            client.setLocalDescription("offer", "expiredDecisionClient", "p".repeat(32));
            var answer = TestSignallingProvider.answer(client.localDescription(), id.fingerprint(), port,
                System.currentTimeMillis() + 2000, TestSignallingProvider.AUDIENCE, false);
            byte[] request = nominatedBinding(answer.token() + ":expiredDecisionClient", answer.password());
            long before = PeerConnection.nativeCreationAttempts();
            socket.send(new DatagramPacket(request, request.length, loopback, port));
            await(() -> endpoint.admissionStats().invalid() > 0);
            assertEquals(before, PeerConnection.nativeCreationAttempts()); assertEquals(0, endpoint.creationAttempts());
            assertEquals(0, endpoint.nativeStats()[2]); assertEquals(0, endpoint.nativeStats()[3]);
            assertEquals(0, endpoint.admissionStats().claims()); assertEquals(0, endpoint.admissionStats().sessions());
        } finally {
            if (endpoint.isRegistered()) endpoint.close().awaitUninterruptibly(); else endpoint.unsafe().closeForcibly();
            endpoint.termination().toCompletableFuture().get(6, TimeUnit.SECONDS);
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
    @Test @Timeout(60) void tokenLengthBoundsAndClientFingerprintAreEnforcedByRealTransport() throws Exception {
        var id = identity();
        for (int passwordLength : new int[]{24, 32, 91}) AdmissionPrimitiveProbe.run(id, passwordLength, false);
        AdmissionPrimitiveProbe.run(id, 24, true);
    }
    private static byte[] nominatedBinding(String username, String password) throws Exception {
        byte[] minimal = AdmissionFixture.binding(username, password);
        int integrityOffset = minimal.length - 24;
        ByteBuffer packet = ByteBuffer.allocate(minimal.length + 24);
        packet.put(minimal, 0, integrityOffset);
        packet.putShort((short)0x24).putShort((short)4).putInt(1853693695);
        packet.putShort((short)0x802a).putShort((short)8).putLong(42);
        packet.putShort((short)0x25).putShort((short)0);
        int signedLength = packet.position();
        packet.putShort((short)8).putShort((short)20);
        packet.putShort(2, (short)(packet.capacity() - 20));
        byte[] transaction = new byte[12]; new java.security.SecureRandom().nextBytes(transaction);
        System.arraycopy(transaction, 0, packet.array(), 8, transaction.length);
        var mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(password.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA1"));
        packet.put(mac.doFinal(Arrays.copyOf(packet.array(), signedLength)));
        return packet.array();
    }
    @Test @Timeout(45) void noControlLazyJoinBothChannelsReplayAndCleanup() throws Exception {
        var id = identity(); var loopback = InetAddress.getByName("127.0.0.1"); int port = 49190;
        var validator = new StatelessAdmissionValidator(TestSignallingProvider.AUDIENCE,60_000);
        validator.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001",TestSignallingProvider.SECRET)));
        var group = new DefaultEventLoopGroup(2);
        var rakGroup = new NioEventLoopGroup(1);
        Channel rak = new ServerBootstrap().group(rakGroup).channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
            .childHandler(new ChannelInboundHandlerAdapter()).bind("127.0.0.1",49191).sync().channel();
        var endpoint = new NativeAdmissionServerChannel(id,validator,new AdmissionGate.Limits(4,8,2,10_000));
        AtomicInteger inboundMask = new AtomicInteger(); AtomicReference<AdmittedNetherNetChildChannel> child = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ServerBootstrap bootstrap = new ServerBootstrap().group(group).channelFactory(() -> endpoint).childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
            @Override protected void initChannel(AdmittedNetherNetChildChannel ch) {
                child.set(ch);
                ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                    boolean reliable = true;
                    @Override public void userEventTriggered(ChannelHandlerContext ctx,Object event) { if(event instanceof NetherNetPacket.Delivery d) reliable=d.reliable(); }
                    @Override protected void channelRead0(ChannelHandlerContext ctx,ByteBuf data) {
                        inboundMask.getAndUpdate(mask -> mask | (reliable?1:2));
                        // Nonzero reader index catches the old transport offset bug.
                        ByteBuf echo=ctx.alloc().buffer(data.readableBytes()+3).writeZero(3).writeBytes(data);echo.skipBytes(3);
                        ctx.writeAndFlush(new NetherNetPacket(echo,reliable));
                    }
                    @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable error) { failure.compareAndSet(null,error);ctx.close(); }
                });
            }
        });
        try {
            bootstrap.bind(new InetSocketAddress(loopback,port)).sync();rakPing(49191);
            assertEquals(0,endpoint.nativeStats()[2]);assertEquals(0,endpoint.admissionStats().claims());
            long beforeInvalid=PeerConnection.nativeCreationAttempts();
            try(var noise=new DatagramSocket()) { byte[] packet=new byte[40];noise.send(new DatagramPacket(packet,packet.length,loopback,port)); }
            await(()->endpoint.nativeStats()[0]>0);
            assertEquals(0, endpoint.nativeStats()[5], "Malformed UDP stays native");
            assertEquals(beforeInvalid,PeerConnection.nativeCreationAttempts());assertEquals(0,endpoint.nativeStats()[3]);
            assertThrows(IllegalStateException.class,()->new IceUdpMuxListener(loopback,port,Runnable::run,request -> CompletableFuture.completedFuture(null)));
            try(PeerConnection client=PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true).withBindAddress(loopback),Runnable::run)) {
                CountDownLatch echoed = new CountDownLatch(2);List<DataChannel> channels=new ArrayList<>();
                for(int index=0;index<2;index++) {
                    boolean reliable=index==0;String label=reliable?"ReliableDataChannel":"UnreliableDataChannel";
                    DataChannel dc=client.createDataChannel(label,DataChannelInitSettings.DEFAULT.withReliability(new DataChannelReliability(!reliable,!reliable,0,0)));
                    channels.add(dc);var decoder=new NetherNetFrameDecoder();byte[] payload=new byte[reliable?20013:7];Arrays.fill(payload,(byte)(reliable?11:22));
                    dc.onMessage.register(DataChannelCallback.Message.handleBinary((d,buffer)->{
                        byte[] frame=new byte[buffer.remaining()];buffer.get(frame);
                        try { byte[] message=decoder.decode(frame,reliable);if(message!=null) {assertArrayEquals(payload,message);echoed.countDown();} }
                        catch(Throwable error){failure.compareAndSet(null,error);}
                    }));
                    dc.onOpen.register(d->{
                        int chunks=(payload.length+9998)/9999;
                        for(int i=0;i<chunks;i++) {int count=Math.min(9999,payload.length-i*9999);ByteBuffer frame=ByteBuffer.allocateDirect(count+1);frame.put((byte)(chunks-i-1)).put(payload,i*9999,count).flip();d.sendMessage(frame);}
                    });
                }
                client.setLocalDescription("offer","clientFixtureUf","p".repeat(32));
                var answer=TestSignallingProvider.answer(client.localDescription(),id.fingerprint(),port,System.currentTimeMillis()+30_000,TestSignallingProvider.AUDIENCE,false);
                var expired=TestSignallingProvider.answer(client.localDescription(),id.fingerprint(),port,System.currentTimeMillis()-1_000,TestSignallingProvider.AUDIENCE,false);
                var wrongHost=TestSignallingProvider.answer(client.localDescription(),id.fingerprint(),port,System.currentTimeMillis()+30_000,"sig_fixture/gs_two/test_boot_001",false);
                String altered=answer.token().substring(0,80)+(answer.token().charAt(80)=='A'?'B':'A')+answer.token().substring(81);
                List<byte[]> rejectedPackets=List.of(
                    StatelessAdmissionValidatorTest.binding(expired.token()+":clientFixtureUf",expired.password()),
                    StatelessAdmissionValidatorTest.binding(wrongHost.token()+":clientFixtureUf",wrongHost.password()),
                    StatelessAdmissionValidatorTest.binding(altered+":clientFixtureUf",answer.password()),
                    StatelessAdmissionValidatorTest.binding(answer.token()+":clientFixtureUf","wrong-stun-integrity-password"),
                    StatelessAdmissionValidatorTest.binding(answer.token()+":differentClientUfrag",answer.password()));
                long beforeNegatives=PeerConnection.nativeCreationAttempts();
                try(var invalid=new DatagramSocket()) {
                    for(byte[] packet:rejectedPackets) {
                        long rejectedBefore = endpoint.admissionStats().invalid();
                        invalid.send(new DatagramPacket(packet,packet.length,loopback,port));
                        await(() -> endpoint.admissionStats().invalid() > rejectedBefore);
                    }
                }
                assertEquals(beforeNegatives,PeerConnection.nativeCreationAttempts());
                assertEquals(0,endpoint.admissionStats().claims());assertEquals(0,endpoint.nativeStats()[2]);assertEquals(0,endpoint.nativeStats()[3]);

                // Issuing an answer changes NO host state. Host has only its profile and key snapshot.
                assertEquals(0,endpoint.admissionStats().claims());assertEquals(0,endpoint.creationAttempts());
                long beforeJoin=PeerConnection.nativeCreationAttempts();
                client.setRemoteDescription(answer.sdp(),SessionDescriptionType.ANSWER);
                assertTrue(echoed.await(12,TimeUnit.SECONDS), "both channels echo through Netty");
                assertNull(failure.get());assertEquals(3,inboundMask.get());
                assertEquals(1,endpoint.creationAttempts());assertEquals(beforeJoin+1,PeerConnection.nativeCreationAttempts());rakPing(49191);
                assertEquals(1,endpoint.nativeStats()[2]);assertEquals(1,endpoint.nativeStats()[3]);
                try(var replay=new DatagramSocket()) {
                    byte[] packet=StatelessAdmissionValidatorTest.binding(answer.token()+":clientFixtureUf",answer.password());
                    replay.send(new DatagramPacket(packet,packet.length,loopback,port));
                    await(()->endpoint.admissionStats().replayRejected()>0);
                }
                assertEquals(1,endpoint.creationAttempts());assertEquals(1,endpoint.nativeStats()[3]);
                assertTrue(endpoint.pollEvents().stream().allMatch(e->e.validationToCreationNanos()>0));
                child.get().close().sync();
                await(()->endpoint.admissionStats().sessions()==0);
                assertEquals(0,child.get().queuedFrames());assertEquals(0,child.get().retainedAssemblyBytes());
            }
            endpoint.close().sync();endpoint.termination().toCompletableFuture().get(5,TimeUnit.SECONDS);
            try(var reuse=new DatagramSocket(new InetSocketAddress(loopback,port))) { assertEquals(port,reuse.getLocalPort()); }
            System.out.println("native-adapter PASS fixedUdp=49190 hostCreations=1 channels=3 replayRejected=true perJoinControl=0 cleanup=true raknetPong=49191");
        } finally { endpoint.close().awaitUninterruptibly();rak.close().awaitUninterruptibly();group.shutdownGracefully(0,2,TimeUnit.SECONDS).sync();rakGroup.shutdownGracefully(0,2,TimeUnit.SECONDS).sync(); }
    }
    @Test @Timeout(30) void providerBoundaryPublishesFreshBootIdentityWithoutClientState() throws Exception {
        var id = identity(); var group = new DefaultEventLoopGroup(1);
        ServerBootstrap bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInboundHandlerAdapter());
        NativeProviderTransport transport = null;
        try {
            long creations = PeerConnection.nativeCreationAttempts();
            transport = NativeProviderTransport.open(bootstrap, new InetSocketAddress("127.0.0.1",49196), id.certificate(), id.privateKey(), AdmissionGate.Limits.defaults()).toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertTrue(transport.hostProfile().toCompletableFuture().isCompletedExceptionally());
            transport.installTicketKeys(List.of(new org.cloudburstmc.netty.signalling.ProviderTransport.TicketKey("K001",TestSignallingProvider.SECRET))).toCompletableFuture().get();
            var first = transport.hostProfile().toCompletableFuture().get();
            assertEquals(id.fingerprint(),first.get("dtlsFingerprint").getAsString());
            assertEquals(49196,first.getAsJsonArray("candidates").get(0).getAsJsonObject().get("port").getAsInt());
            String incarnation = first.getAsJsonObject("statelessAdmission").get("incarnation").getAsString();
            assertTrue(incarnation.matches("[0-9a-f]{32}"));
            var command = new com.google.gson.JsonObject();command.addProperty("kind","join-admission");
            assertEquals(org.cloudburstmc.netty.signalling.ProviderTransport.ApplyResult.REJECTED,transport.applyState("join-admission").toCompletableFuture().get());
            assertEquals(0,transport.channel().admissionStats().claims());assertEquals(0,transport.channel().nativeStats()[2]);
            assertEquals(creations,PeerConnection.nativeCreationAttempts());
            transport.installTicketKeys(List.of(new org.cloudburstmc.netty.signalling.ProviderTransport.TicketKey("K001",TestSignallingProvider.SECRET,0,System.currentTimeMillis()+60_000),
                new org.cloudburstmc.netty.signalling.ProviderTransport.TicketKey("K002","next-background-key-of-at-least-32-bytes"))).toCompletableFuture().get();
            assertEquals("K002",transport.hostProfile().toCompletableFuture().get().get("credentialKeyId").getAsString());
            transport.drain().toCompletableFuture().get();assertTrue(transport.hostProfile().toCompletableFuture().isCompletedExceptionally());
            transport.close().toCompletableFuture().get(5,TimeUnit.SECONDS);
            transport = NativeProviderTransport.open(bootstrap, new InetSocketAddress("127.0.0.1",49196), id.certificate(), id.privateKey(), AdmissionGate.Limits.defaults()).toCompletableFuture().get(5,TimeUnit.SECONDS);
            transport.installTicketKeys(List.of(new org.cloudburstmc.netty.signalling.ProviderTransport.TicketKey("K001",TestSignallingProvider.SECRET))).toCompletableFuture().get();
            String restarted = transport.hostProfile().toCompletableFuture().get().getAsJsonObject("statelessAdmission").get("incarnation").getAsString();
            assertNotEquals(incarnation,restarted);assertNotEquals(NativeProviderTransport.audience(incarnation),NativeProviderTransport.audience(restarted));
            assertEquals(creations,PeerConnection.nativeCreationAttempts());
        } finally { if (transport != null) transport.close().toCompletableFuture().get(5,TimeUnit.SECONDS);group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync(); }
    }

    @Test @Timeout(40) void simultaneousClientsRespectNativeCapacityUntilActualTeardown() throws Exception {
        var id=identity();var group=new DefaultEventLoopGroup(2);var clients=new ArrayList<PeerConnection>();
        var children=new CopyOnWriteArrayList<AdmittedNetherNetChildChannel>();
        var validator=new StatelessAdmissionValidator(TestSignallingProvider.AUDIENCE,60000);
        validator.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001",TestSignallingProvider.SECRET)));
        var endpoint=new NativeAdmissionServerChannel(id,validator,new AdmissionGate.Limits(2,4,2,10000));
        try {
            new ServerBootstrap().group(group).channelFactory(()->endpoint).childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
                @Override protected void initChannel(AdmittedNetherNetChildChannel child) { children.add(child); }
            }).bind("127.0.0.1",49198).sync();
            var answers=new ArrayList<TestSignallingProvider.Answer>();var opens=new AtomicIntegerArray(3);
            for(int i=0;i<3;i++) {
                final int index=i;
                var client=PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true).withBindAddress(InetAddress.getByName("127.0.0.1")),Runnable::run);
                clients.add(client);
                for(boolean reliable:new boolean[]{true,false}) {
                    var dc=client.createDataChannel(reliable?"ReliableDataChannel":"UnreliableDataChannel",DataChannelInitSettings.DEFAULT.withReliability(new DataChannelReliability(!reliable,!reliable,0,0)));
                    dc.onOpen.register(ignored->opens.incrementAndGet(index));
                }
                client.setLocalDescription("offer","multiClient"+i,"p".repeat(32));
                answers.add(TestSignallingProvider.answer(client.localDescription(),id.fingerprint(),49198,System.currentTimeMillis()+30000,TestSignallingProvider.AUDIENCE,false));
            }
            long before=PeerConnection.nativeCreationAttempts();
            clients.get(0).setRemoteDescription(answers.get(0).sdp(),SessionDescriptionType.ANSWER);
            clients.get(1).setRemoteDescription(answers.get(1).sdp(),SessionDescriptionType.ANSWER);
            await(()->opens.get(0)==2 && opens.get(1)==2);
            assertEquals(2,endpoint.liveNativePeers());assertEquals(2,endpoint.nativeStats()[2]);
            clients.get(2).setRemoteDescription(answers.get(2).sdp(),SessionDescriptionType.ANSWER);
            await(()->endpoint.admissionStats().capacityRejected()>0);
            assertEquals(2,endpoint.creationAttempts());assertEquals(before+2,PeerConnection.nativeCreationAttempts());assertEquals(2,endpoint.admissionStats().claims());
            var closing=children.get(0);closing.close().sync();closing.nativeTermination().toCompletableFuture().get(5,TimeUnit.SECONDS);
            // Third client's normal ICE retries can claim the released slot; used tokens remain tombstoned.
            await(()->{assertTrue(endpoint.nativeStats()[2]<=2);assertTrue(endpoint.liveNativePeers()<=2);return opens.get(2)==2;});
            assertEquals(3,endpoint.creationAttempts());assertEquals(before+3,PeerConnection.nativeCreationAttempts());
            assertEquals(2,endpoint.liveNativePeers());assertEquals(2,endpoint.nativeStats()[2]);assertEquals(3,endpoint.admissionStats().claims());
            System.out.println("native-capacity PASS simultaneousClients=2 thirdRetriesAfterTeardown=true maxNativePeers=2");
        } finally {
            for(var client:clients) assertTrue(client.closeAndAwait(Duration.ofSeconds(5)));
            endpoint.close().awaitUninterruptibly();endpoint.termination().toCompletableFuture().get(6,TimeUnit.SECONDS);
            group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();
        }
    }

}
