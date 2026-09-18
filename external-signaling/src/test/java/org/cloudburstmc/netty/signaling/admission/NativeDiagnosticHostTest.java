package org.cloudburstmc.netty.signaling.admission;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.diagnostic.*;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.Key;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tel.schich.libdatachannel.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** Actual signed permit/answer + incoming UDP gate, using test-owned workload/installed policy. No game/login. */
@Tag("native")
class NativeDiagnosticHostTest {
    @TempDir Path directory;
    static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String id() { byte[] value = new byte[16]; new SecureRandom().nextBytes(value); return hex(value); }
    static KeyPair keyPair() throws Exception { KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp384r1")); return generator.generateKeyPair(); }
    static int port(InetAddress bind) throws Exception { try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(bind,0))) { return socket.getLocalPort(); } }
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    NativeHostIdentity identity() throws Exception {
        Path cert = directory.resolve("host.crt"), key = directory.resolve("host.key");
        Process process = new ProcessBuilder("openssl","req","-x509","-newkey","ec","-pkeyopt","ec_paramgen_curve:prime256v1","-nodes","-keyout",key.toString(),"-out",cert.toString(),"-days","1","-subj","/CN=diagnostic-test-only")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(10,TimeUnit.SECONDS)); assertEquals(0,process.exitValue()); return NativeHostIdentity.load(cert,key);
    }
    static final class Client implements AutoCloseable {
        final PeerConnection peer;
        final DataChannel[] channels = new DataChannel[2];
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final ArrayBlockingQueue<byte[]> reliable = new ArrayBlockingQueue<>(12), unreliable = new ArrayBlockingQueue<>(12);
        final String ufrag = id(), password = "p".repeat(24);
        final long expiry;
        DiagnosticExchange exchange;
        Credentials credentials;
        Claims claims;
        Client(InetAddress bind, long expiry) throws Exception {
            this.expiry = expiry; int localPort = port(bind);
            peer = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withBindAddress(bind).withDisableAutoNegotiation(true)
                    .withEnableIceUdpMux(true).withPortRangeBegin(localPort).withPortRangeEnd(localPort).withMtu(1248).withMaxMessageSize(MAX_MESSAGE_SIZE), Runnable::run);
            for (int i=0;i<2;i++) {
                int index=i;
                channels[i]=peer.createDataChannel(i==0?"ReliableDataChannel":"UnreliableDataChannel",DataChannelInitSettings.DEFAULT.withReliability(DataChannelReliability.DEFAULT.withUnordered(i==1).withUnreliable(i==1).withMaxRetransmits(0)));
                channels[i].onMessage.register(new DataChannelCallback.Message() {
                    public void onText(DataChannel channel,String text) { failure.compareAndSet(null,new AssertionError("text")); }
                    public void onBinary(DataChannel channel,ByteBuffer data) {
                        if(data.remaining()>MAX_FRAME_BYTES) {failure.compareAndSet(null,new AssertionError("oversize"));return;}
                        byte[] copy=new byte[data.remaining()];data.get(copy);
                        if(!(index==0?reliable:unreliable).offer(copy)) failure.compareAndSet(null,new AssertionError("queue"));
                    }
                });
            }
            CountDownLatch gathered=new CountDownLatch(1);
            peer.onGatheringStateChange.register((p,state)->{if(state==GatheringState.RTC_GATHERING_COMPLETE)gathered.countDown();});
            peer.setLocalDescription("offer",ufrag,password); assertTrue(gathered.await(3,TimeUnit.SECONDS));
        }
        void connect(NativeHostIdentity identity,Context context,Key key,int family,String target,int port,boolean wrongDtls) throws Exception {
            connect(identity, context, key, family, target, port, wrongDtls, 7, null, null);
        }
        void connect(NativeHostIdentity identity,Context context,Key key,int family,String target,int port,boolean wrongDtls,
                long candidateRevision, DiagnosticAnswerCodec.Signer suppliedSigner, DiagnosticAnswerCodec.Catalog suppliedCatalog) throws Exception {
            String offer=peer.localDescription();
            String fingerprint=TestSignalingProvider.field(offer,"fingerprint").substring(8).replace(":","").toLowerCase(Locale.ROOT);
            if(wrongDtls) { String wrong=(fingerprint.charAt(0)=='0'?"1":"0")+fingerprint.substring(1); offer=offer.replace(TestSignalingProvider.field(offer,"fingerprint"),"sha-256 "+HexFormat.ofDelimiter(":").withUpperCase().formatHex(HexFormat.of().parseHex(wrong)));fingerprint=wrong; }
            claims=new Claims(expiry,fingerprint,password,id(),hash(utf8(offer)),candidateRevision,family,DiagnosticAdmissionCodec.address(family,target),port,1);
            KeyPair prober=keyPair(); var assertion=DiagnosticAssertionCodec.sign(context,claims,ufrag,prober);
            credentials=DiagnosticAdmissionCodec.issue(context,key,claims,ufrag,utf8(offer),assertion,key.retireAt(),Clock.system());
            String answer="v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=setup:active\r\na=ice-ufrag:"+credentials.localUfrag()+"\r\na=ice-pwd:"+credentials.icePwd()+"\r\na=fingerprint:"+identity.fingerprint()+"\r\na=sctp-port:5000\r\na=max-message-size:262144\r\na=candidate:1 1 UDP 2130706431 "+target+" "+port+" typ host\r\na=end-of-candidates\r\n";
            KeyPair provider=keyPair(); byte[] encoded=provider.getPublic().getEncoded();
            var catalog=new DiagnosticAnswerCodec.Catalog(context.providerOrigin(),0,key.retireAt(),List.of(new DiagnosticAnswerCodec.VerificationKey("provider-diagnostic","test-answer",hex(Arrays.copyOfRange(encoded,encoded.length-97,encoded.length)),0,key.retireAt())));
            var selectedCatalog = suppliedCatalog == null ? catalog : suppliedCatalog;
            var signer = suppliedSigner == null ? new DiagnosticAnswerCodec.Signer("provider-diagnostic","test-answer",provider.getPrivate()) : suppliedSigner;
            var expected=new DiagnosticAnswerCodec.Expected(context,claims,ufrag,identity.fingerprint().substring(8).replace(":","").toLowerCase(Locale.ROOT));
            String signed=DiagnosticAnswerCodec.sign(expected,utf8(answer),signer,()->selectedCatalog,DiagnosticAnswerCodec.Options.system());
            try(var verified=DiagnosticAnswerCodec.verify(expected,signed,()->selectedCatalog,DiagnosticAnswerCodec.Options.system())) {
                assertNotNull(verified); peer.setRemoteDescription(new String(verified.takeSdp(),StandardCharsets.UTF_8),SessionDescriptionType.ANSWER);
            }
        }
        void send(int channel,byte[] bytes) {channels[channel].sendMessage(ByteBuffer.allocateDirect(bytes.length).put(bytes).flip());}
        void start(boolean wrongPing) throws Exception {
            await(()->channels[0].isOpen()&&channels[1].isOpen());
            exchange=new DiagnosticExchange(claims.attemptIdHex(),false,(channel,bytes)->{
                if(wrongPing)bytes[8]^=1;
                send(channel,bytes);
            });exchange.start();
        }
        void tick() {
            if(exchange==null)return;
            for(int channel=0;channel<2;channel++) {byte[] bytes;while((bytes=(channel==0?reliable:unreliable).poll())!=null)exchange.receive(channel,bytes);}
        }
        public void close() {assertTrue(peer.closeAndAwait(Duration.ofSeconds(5)));}
    }
    @Test @Timeout(35) void signedGateQualifiesBothFamiliesWithoutPlayerPromotion() throws Exception {
        var identity=identity();
        for(String address:List.of("127.0.0.1","::1")) {
            InetAddress bind=InetAddress.getByName(address);int port=port(bind),family=bind instanceof Inet6Address?6:4;
            long expiry=(System.currentTimeMillis()+20000)/1000*1000;
            Context context=new Context("https://provider.example","test-host",id(),1);
            Key key=new Key("D001","test-only-diagnostic-secret-32-bytes",0,expiry+10000);
            AtomicInteger playerValidations=new AtomicInteger(),playerChildren=new AtomicInteger();
            var endpoint=new NativeAdmissionServerChannel(identity,(request,now)->{playerValidations.incrementAndGet();return null;},AdmissionGate.Limits.defaults());
            var group=new DefaultEventLoopGroup(1);
            try {
                new ServerBootstrap().group(group).channelFactory(()->endpoint).childHandler(new ChannelInitializer<Channel>() {protected void initChannel(Channel channel){playerChildren.incrementAndGet();}}).bind(bind,port).sync();
                var policy=new DiagnosticHostPolicy(context,List.of(key),Set.of(new DiagnosticHostPolicy.Endpoint(family,DiagnosticAdmissionCodec.address(family,address),port,7)),expiry+10000);
                var gate=endpoint.enableDiagnostics(policy).toCompletableFuture().get(3,TimeUnit.SECONDS);
                try(Client client=new Client(bind,expiry)) {
                    client.connect(identity,context,key,family,address,port,false);client.start(false);
                    var reports=new ArrayList<NativeDiagnosticHostGate.Result>();
                    await(()->{client.tick();reports.addAll(gate.pollResults());return !reports.isEmpty();});
                    assertEquals(1,reports.size());var report=reports.get(0);
                    assertTrue(report.success(),report.toString());assertTrue(client.exchange.complete());
                    assertNull(client.failure.get());
                    assertEquals(0,gate.stats().liveNativePeers());assertEquals(0,gate.stats().active());assertEquals(1,gate.stats().retainedAttempts());
                    assertEquals(0,playerValidations.get());assertEquals(0,playerChildren.get());assertEquals(0,endpoint.creationAttempts());assertTrue(endpoint.pollEvents().isEmpty());
                    assertEquals(family,report.selectedLocal().getAddress() instanceof Inet6Address?6:4);
                    assertEquals(context,report.context());assertEquals(key.keyId(),report.keyId());assertEquals(client.claims.offerDigestHex(),report.offerDigestHex());
                    assertEquals(client.claims.clientFingerprintHex(),report.clientFingerprintHex());assertEquals(expiry,report.expiresAt());assertEquals(port,report.selectedLocal().getPort());
                    assertEquals(1,report.sentFrames());assertEquals(1,report.receivedFrames());
                    byte[] replay=StatelessAdmissionValidatorTest.binding(client.credentials.localUfrag()+":"+client.ufrag,client.credentials.icePwd());
                    try(DatagramSocket socket=new DatagramSocket(new InetSocketAddress(bind,0))) {socket.send(new DatagramPacket(replay,replay.length,bind,port));await(()->gate.stats().rejected()>0);}
                    assertEquals(0,gate.stats().active());assertEquals(0,playerValidations.get());
                }
            } finally {endpoint.close().awaitUninterruptibly();endpoint.termination().toCompletableFuture().get(6,TimeUnit.SECONDS);group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();}
        }
    }

    @Test @Timeout(40) void wrongIdentityPingUnexpectedChannelsAndWithdrawalNeverQualify() throws Exception {
        var identity=identity();
        for(String mode:List.of("dtls","ping","extra-channel","text","withdraw","empty-keys","empty-endpoints","generation")) {
            InetAddress bind=InetAddress.getByName("127.0.0.1");int port=port(bind);
            long expiry=(System.currentTimeMillis()+15000)/1000*1000;
            Context context=new Context("https://provider.example","test-host",id(),1);
            Key key=new Key("D001","test-only-diagnostic-secret-32-bytes",0,expiry+10000);
            AtomicInteger playerValidations=new AtomicInteger();
            var endpoint=new NativeAdmissionServerChannel(identity,(request,now)->{playerValidations.incrementAndGet();return null;},AdmissionGate.Limits.defaults());
            var group=new DefaultEventLoopGroup(1);
            try {
                new ServerBootstrap().group(group).channelFactory(()->endpoint).childHandler(new ChannelInitializer<Channel>() {protected void initChannel(Channel channel){fail("diagnostic promoted to player");}}).bind(bind,port).sync();
                var target=new DiagnosticHostPolicy.Endpoint(4,DiagnosticAdmissionCodec.address(4,"127.0.0.1"),port,7);
                var policy=new DiagnosticHostPolicy(context,List.of(key),Set.of(target),expiry+10000);
                var gate=endpoint.enableDiagnostics(policy).toCompletableFuture().get(3,TimeUnit.SECONDS);
                try(Client client=new Client(bind,expiry)) {
                    client.connect(identity,context,key,4,"127.0.0.1",port,mode.equals("dtls"));
                    if(!mode.equals("dtls")) {
                        await(()->client.channels[0].isOpen()&&client.channels[1].isOpen());
                        switch(mode) {
                            case "ping" -> client.start(true);
                            case "extra-channel" -> client.peer.createDataChannel("unexpected");
                            case "text" -> client.channels[0].sendMessage("invalid diagnostic text");
                            case "withdraw" -> gate.replacePolicy(new DiagnosticHostPolicy(context,List.of(new Key("D002","other-test-only-secret-at-least32bytes",0,expiry+10000)),Set.of(target),expiry+10000));
                            case "empty-keys" -> gate.replacePolicy(new DiagnosticHostPolicy(context,List.of(),Set.of(target),expiry+10000));
                            case "empty-endpoints" -> gate.replacePolicy(new DiagnosticHostPolicy(context,List.of(key),Set.of(),expiry+10000));
                            case "generation" -> gate.replacePolicy(new DiagnosticHostPolicy(new Context(context.providerOrigin(),context.hostId(),context.incarnation(),2),List.of(key),Set.of(target),expiry+10000));
                        }
                    }
                    var reports=new ArrayList<NativeDiagnosticHostGate.Result>();
                    await(()->{reports.addAll(gate.pollResults());return !reports.isEmpty();});
                    assertEquals(1,reports.size(),mode);assertFalse(reports.get(0).success(),mode);assertEquals(0,reports.get(0).sentFrames(),mode);
                    assertEquals(0,gate.stats().liveNativePeers(),mode);assertEquals(1,gate.stats().retainedAttempts(),mode);
                    assertEquals(0,playerValidations.get(),mode);assertEquals(0,endpoint.creationAttempts(),mode);assertTrue(endpoint.pollEvents().isEmpty(),mode);
                    if(mode.startsWith("empty-")) {
                        gate.replacePolicy(policy);
                        try(Client fresh=new Client(bind,expiry)) {
                            fresh.connect(identity,context,key,4,"127.0.0.1",port,false);fresh.start(false);var recovered=new ArrayList<NativeDiagnosticHostGate.Result>();
                            await(()->{fresh.tick();recovered.addAll(gate.pollResults());return !recovered.isEmpty();});
                            assertTrue(recovered.get(0).success(),recovered.toString());assertEquals(2,gate.stats().retainedAttempts());
                        }
                    }
                }
            } finally {endpoint.close().awaitUninterruptibly();endpoint.termination().toCompletableFuture().get(6,TimeUnit.SECONDS);group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();}
        }
    }

    @Test @Timeout(25) void disabledMalformedAndUninstalledRevisionCannotFallThroughToPlayer() throws Exception {
        var identity=identity();InetAddress bind=InetAddress.getByName("127.0.0.1");int port=port(bind);
        long expiry=(System.currentTimeMillis()+15000)/1000*1000;Context context=new Context("https://provider.example","test-host",id(),1);
        Key key=new Key("D001","test-only-diagnostic-secret-32-bytes",0,expiry+10000);AtomicInteger playerValidations=new AtomicInteger();
        var endpoint=new NativeAdmissionServerChannel(identity,(request,now)->{playerValidations.incrementAndGet();return null;},AdmissionGate.Limits.defaults());var group=new DefaultEventLoopGroup(1);
        try {
            new ServerBootstrap().group(group).channelFactory(()->endpoint).childHandler(new ChannelInitializer<Channel>() {protected void initChannel(Channel channel){fail("player");}}).bind(bind,port).sync();
            byte[] malformed=StatelessAdmissionValidatorTest.binding("NXD1malformed:clientFixtureUf","p".repeat(24));
            try(DatagramSocket socket=new DatagramSocket(new InetSocketAddress(bind,0))) {socket.send(new DatagramPacket(malformed,malformed.length,bind,port));await(()->endpoint.nativeStats()[0]>0);}
            assertEquals(0,playerValidations.get());assertEquals(0,endpoint.liveNativePeers());
            var gate=endpoint.enableDiagnostics(new DiagnosticHostPolicy(context,List.of(key),Set.of(new DiagnosticHostPolicy.Endpoint(4,DiagnosticAdmissionCodec.address(4,"127.0.0.1"),port,8)),expiry+10000)).toCompletableFuture().get();
            try(Client client=new Client(bind,expiry)) {
                var before=NativeDiagnostics.creationAttempts();client.connect(identity,context,key,4,"127.0.0.1",port,false);
                await(()->gate.stats().rejected()>0);NativeDiagnostics.assertCreations(before,0);assertEquals(0,gate.stats().active());assertEquals(0,gate.stats().retainedAttempts());assertEquals(0,playerValidations.get());
            }
        } finally {endpoint.close().awaitUninterruptibly();endpoint.termination().toCompletableFuture().get(6,TimeUnit.SECONDS);group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();}
    }

    @Test @Timeout(25) void simultaneousPlayerSurvivesDiagnosticAndSharedCapacityRejectsExtraPeer() throws Exception {
        var identity=identity();InetAddress bind=InetAddress.getByName("127.0.0.1");int port=port(bind);
        long expiry=(System.currentTimeMillis()+15000)/1000*1000;Context context=new Context("https://provider.example","test-host",id(),1);
        Key key=new Key("D001","test-only-diagnostic-secret-32-bytes",0,expiry+10000);
        var validator=new StatelessAdmissionValidator(TestSignalingProvider.AUDIENCE,60_000);validator.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001",TestSignalingProvider.SECRET)));
        var endpoint=new NativeAdmissionServerChannel(identity,validator,new AdmissionGate.Limits(2,64,2,15_000));var group=new DefaultEventLoopGroup(1);AtomicInteger children=new AtomicInteger(),echoes=new AtomicInteger();
        try(PeerConnection player=PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withBindAddress(bind).withDisableAutoNegotiation(true))) {
            new ServerBootstrap().group(group).channelFactory(()->endpoint).childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {protected void initChannel(AdmittedNetherNetChildChannel channel) {
                children.incrementAndGet();channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {protected void channelRead0(ChannelHandlerContext ctx,ByteBuf bytes){ctx.writeAndFlush(bytes.retain());}});
            }}).bind(bind,port).sync();
            DataChannel reliable=player.createDataChannel("ReliableDataChannel");player.createDataChannel("UnreliableDataChannel",DataChannelInitSettings.DEFAULT.withReliability(DataChannelReliability.DEFAULT.withUnordered(true).withUnreliable(true).withMaxRetransmits(0)));
            reliable.onMessage.register(DataChannelCallback.Message.handleBinary((channel,bytes)->{if(bytes.remaining()==2&&bytes.get()==0&&bytes.get()==42)echoes.incrementAndGet();}));
            player.setLocalDescription("offer","playerFixture","p".repeat(24));var answer=TestSignalingProvider.answer(player.localDescription(),identity.fingerprint(),port,expiry,TestSignalingProvider.AUDIENCE,false);player.setRemoteDescription(answer.sdp(),SessionDescriptionType.ANSWER);
            await(()->reliable.isOpen());reliable.sendMessage(ByteBuffer.allocateDirect(2).put((byte)0).put((byte)42).flip());await(()->echoes.get()==1);
            // The periodic player event can legitimately follow the first echo. Fence it before asserting diagnostic silence.
            var playerEvents=new ArrayList<NativeAdmissionServerChannel.Event>();
            await(()->{playerEvents.addAll(endpoint.pollEvents());return playerEvents.stream().anyMatch(event->event.stage().equals("ticket.data_channels_open"));});
            var gate=endpoint.enableDiagnostics(new DiagnosticHostPolicy(context,List.of(key),Set.of(new DiagnosticHostPolicy.Endpoint(4,DiagnosticAdmissionCodec.address(4,"127.0.0.1"),port,7)),expiry+10000)).toCompletableFuture().get();
            try(Client diagnostic=new Client(bind,expiry)) {
                diagnostic.connect(identity,context,key,4,"127.0.0.1",port,false);await(()->diagnostic.channels[0].isOpen()&&diagnostic.channels[1].isOpen());assertEquals(2,endpoint.liveNativePeers());
                try(Client extra=new Client(bind,expiry)) {
                    var created=NativeDiagnostics.creationAttempts();long received=endpoint.nativeStats()[0];extra.connect(identity,context,key,4,"127.0.0.1",port,false);await(()->endpoint.nativeStats()[0]>received);
                    Thread.sleep(200);NativeDiagnostics.assertCreations(created,0);assertEquals(2,endpoint.liveNativePeers());assertEquals(1,gate.stats().active());
                }
                diagnostic.start(false);var reports=new ArrayList<NativeDiagnosticHostGate.Result>();await(()->{diagnostic.tick();reports.addAll(gate.pollResults());return !reports.isEmpty();});assertTrue(reports.get(0).success(),reports.toString());
                assertEquals(1,endpoint.liveNativePeers());assertEquals(1,children.get());assertEquals(1,endpoint.creationAttempts());assertTrue(endpoint.pollEvents().isEmpty());
                reliable.sendMessage(ByteBuffer.allocateDirect(2).put((byte)0).put((byte)42).flip());await(()->echoes.get()==2);
            }
            assertTrue(player.closeAndAwait(Duration.ofSeconds(5)));
        } finally {endpoint.close().awaitUninterruptibly();endpoint.termination().toCompletableFuture().get(6,TimeUnit.SECONDS);group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();}
    }

    @Test @Timeout(25) void fourPendingDiagnosticsExpireWithoutRefreshingOrReopeningOldAttempt() throws Exception {
        var identity=identity();InetAddress bind=InetAddress.getByName("127.0.0.1");int port=port(bind);
        long expiry=(System.currentTimeMillis()+5000)/1000*1000;Context context=new Context("https://provider.example","test-host",id(),1);
        Key key=new Key("D001","test-only-diagnostic-secret-32-bytes",0,expiry+10000);
        var endpoint=new NativeAdmissionServerChannel(identity,(request,now)->{fail("player fallback");return null;},AdmissionGate.Limits.defaults());var group=new DefaultEventLoopGroup(1);var clients=new ArrayList<Client>();
        try {
            new ServerBootstrap().group(group).channelFactory(()->endpoint).childHandler(new ChannelInitializer<Channel>() {protected void initChannel(Channel channel){fail("player child");}}).bind(bind,port).sync();
            var gate=endpoint.enableDiagnostics(new DiagnosticHostPolicy(context,List.of(key),Set.of(new DiagnosticHostPolicy.Endpoint(4,DiagnosticAdmissionCodec.address(4,"127.0.0.1"),port,7)),expiry+10000)).toCompletableFuture().get();
            for(int i=0;i<4;i++) {Client client=new Client(bind,expiry);clients.add(client);client.connect(identity,context,key,4,"127.0.0.1",port,false);await(()->client.channels[0].isOpen()&&client.channels[1].isOpen());}
            assertEquals(4,gate.stats().active());assertEquals(4,gate.stats().pending());
            try(Client extra=new Client(bind,expiry)) {var before=NativeDiagnostics.creationAttempts();extra.connect(identity,context,key,4,"127.0.0.1",port,false);await(()->gate.stats().rejected()>0);NativeDiagnostics.assertCreations(before,0);}
            var reports=new ArrayList<NativeDiagnosticHostGate.Result>();await(()->{reports.addAll(gate.pollResults());return reports.size()==4&&gate.stats().active()==0&&gate.stats().retainedAttempts()==0;});
            assertTrue(reports.stream().noneMatch(NativeDiagnosticHostGate.Result::success));assertEquals(0,gate.stats().liveNativePeers());
            Client original=clients.get(0);byte[] replay=StatelessAdmissionValidatorTest.binding(original.credentials.localUfrag()+":"+original.ufrag,original.credentials.icePwd());long rejected=gate.stats().rejected();var created=NativeDiagnostics.creationAttempts();
            try(DatagramSocket socket=new DatagramSocket(new InetSocketAddress(bind,0))) {socket.send(new DatagramPacket(replay,replay.length,bind,port));await(()->gate.stats().rejected()>rejected);}
            NativeDiagnostics.assertCreations(created,0);assertEquals(0,gate.stats().retainedAttempts());
        } finally {for(Client client:clients)client.close();endpoint.close().awaitUninterruptibly();endpoint.termination().toCompletableFuture().get(6,TimeUnit.SECONDS);group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();}
    }

    @Test @Timeout(35) void retainedFailedPingAttemptsStayBoundedAndAreNotReissued() throws Exception {
        var identity=identity();InetAddress bind=InetAddress.getByName("127.0.0.1");int port=port(bind);
        long expiry=(System.currentTimeMillis()+30000)/1000*1000;Context context=new Context("https://provider.example","test-host",id(),1);Key key=new Key("D001","test-only-diagnostic-secret-32-bytes",0,expiry+10000);
        var endpoint=new NativeAdmissionServerChannel(identity,(request,now)->{fail("player fallback");return null;},AdmissionGate.Limits.defaults());var group=new DefaultEventLoopGroup(1);
        try {
            new ServerBootstrap().group(group).channelFactory(()->endpoint).childHandler(new ChannelInitializer<Channel>() {protected void initChannel(Channel channel){fail("player child");}}).bind(bind,port).sync();
            var gate=endpoint.enableDiagnostics(new DiagnosticHostPolicy(context,List.of(key),Set.of(new DiagnosticHostPolicy.Endpoint(4,DiagnosticAdmissionCodec.address(4,"127.0.0.1"),port,7)),expiry+10000)).toCompletableFuture().get();
            for(int i=0;i<16;i++)try(Client client=new Client(bind,expiry)) {
                client.connect(identity,context,key,4,"127.0.0.1",port,false);client.start(true);var reports=new ArrayList<NativeDiagnosticHostGate.Result>();await(()->{reports.addAll(gate.pollResults());return !reports.isEmpty();});
                assertFalse(reports.get(0).success());assertEquals(0,gate.stats().active());assertEquals(i+1,gate.stats().retainedAttempts());
            }
            try(Client client=new Client(bind,expiry)) {var created=NativeDiagnostics.creationAttempts();client.connect(identity,context,key,4,"127.0.0.1",port,false);await(()->gate.stats().rejected()>0);NativeDiagnostics.assertCreations(created,0);assertEquals(16,gate.stats().retainedAttempts());}
        } finally {endpoint.close().awaitUninterruptibly();endpoint.termination().toCompletableFuture().get(6,TimeUnit.SECONDS);group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();}
    }
}
