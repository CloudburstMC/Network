/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.admission.*;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.Key;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** Actual local JNI/UDP/DTLS/SCTP tests. Test-owned facade and installed host policy; no public traffic/gameplay. */
@Tag("native")
class NativeDiagnosticProbeAttemptTest {
    @TempDir Path directory;
    static String id() { byte[] value = new byte[16]; new SecureRandom().nextBytes(value); return hex(value); }
    static KeyPair keyPair() throws Exception { KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp384r1"));return generator.generateKeyPair(); }
    static int port(InetAddress bind) throws Exception { try(var socket=new DatagramSocket(new InetSocketAddress(bind,0))){return socket.getLocalPort();} }
    static void await(BooleanSupplier condition) throws Exception { long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(5);assertTrue(condition.getAsBoolean()); }
    NativeHostIdentity identity(String name) throws Exception {
        Path cert=directory.resolve(name+".crt"),key=directory.resolve(name+".key");
        var process=new ProcessBuilder("openssl","req","-x509","-newkey","ec","-pkeyopt","ec_paramgen_curve:prime256v1","-nodes","-keyout",key.toString(),"-out",cert.toString(),"-days","1","-subj","/CN=probe-test-only").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try { assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(0,process.exitValue()); }
        finally { process.destroyForcibly(); }
        return NativeHostIdentity.load(cert,key);
    }
    final class Fixture implements AutoCloseable {
        final InetAddress bind; final int port, localPort; final long expiry;
        final Context context=new Context("https://provider.example","probe-host",id(),1);
        final Key permit; final KeyPair provider=keyPair(); final NativeHostIdentity identity=identity("host");
        final AtomicReference<DiagnosticAnswerCodec.Catalog> catalog=new AtomicReference<>();
        final AtomicBoolean authorized=new AtomicBoolean(true); final AtomicInteger players=new AtomicInteger(),signalingCalls=new AtomicInteger();
        final NativeAdmissionServerChannel host; final DefaultEventLoopGroup group=new DefaultEventLoopGroup(1);
        final NativeDiagnosticHostGate gate; final DiagnosticHostPolicy policy; final NativeDiagnosticProbeAttempt.Job job;
        Fixture(String address,int duration) throws Exception { this(address,duration,true); }
        Fixture(String address,int duration,boolean ping) throws Exception {
            bind=InetAddress.getByName(address);port=port(bind);localPort=port(bind);int family=bind instanceof Inet6Address?6:4;
            expiry=(System.currentTimeMillis()+duration)/1000*1000;
            permit=new Key("D001","random-test-permit-"+id(),0,expiry+10000);
            byte[] encoded=provider.getPublic().getEncoded();
            catalog.set(new DiagnosticAnswerCodec.Catalog(context.providerOrigin(),0,expiry+10000,List.of(new DiagnosticAnswerCodec.VerificationKey("provider-diagnostic","provider-key",hex(Arrays.copyOfRange(encoded,encoded.length-97,encoded.length)),0,expiry+10000))));
            var target=new DiagnosticHostPolicy.Endpoint(family,DiagnosticAdmissionCodec.address(family,address),port,7);
            policy=new DiagnosticHostPolicy(context,List.of(permit),Set.of(target),expiry+10000);
            job=new NativeDiagnosticProbeAttempt.Job(context,id(),target,identity.fingerprint().substring(8).replace(":","").toLowerCase(Locale.ROOT),expiry,ping);
            host=new NativeAdmissionServerChannel(identity,(request,now)->{players.incrementAndGet();return null;},AdmissionGate.Limits.defaults());
            new ServerBootstrap().group(group).channelFactory(()->host).childHandler(new ChannelInitializer<Channel>(){protected void initChannel(Channel channel){players.incrementAndGet();}}).bind(bind,port).sync();
            gate=host.enableDiagnostics(policy).toCompletableFuture().get(3,TimeUnit.SECONDS);
        }
        NativeDiagnosticProbeAttempt attempt() { return new NativeDiagnosticProbeAttempt(job,new InetSocketAddress(bind,localPort),catalog::get,authorized::get,Clock.system(),true); }
        String sign(NativeDiagnosticProbeAttempt.Request request,String fingerprint, String address,int targetPort) {
            signalingCalls.incrementAndGet();
            var claims=request.claims(); assertEquals(job.context(),request.context());assertEquals(job.attemptIdHex(),claims.attemptIdHex());assertEquals(job.expiresAt(),claims.expiresAt());
            assertTrue(DiagnosticAssertionCodec.verify(context,claims,request.ufrag(),request.assertion()));
            byte[] owned=request.offer();byte original=owned[0];owned[0]^=1;assertEquals(original,request.offer()[0]);
            var credentials=DiagnosticAdmissionCodec.issue(context,permit,claims,request.ufrag(),request.offer(),request.assertion(),permit.retireAt(),Clock.system());
            String answer="v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=setup:active\r\na=ice-ufrag:"+credentials.localUfrag()+"\r\na=ice-pwd:"+credentials.icePwd()+"\r\na=fingerprint:sha-256 "+HexFormat.ofDelimiter(":").withUpperCase().formatHex(HexFormat.of().parseHex(fingerprint))+"\r\na=sctp-port:5000\r\na=max-message-size:262144\r\na=candidate:1 1 UDP 2130706431 "+address+" "+targetPort+" typ host\r\na=end-of-candidates\r\n";
            var expected=new DiagnosticAnswerCodec.Expected(context,claims,request.ufrag(),fingerprint);
            return DiagnosticAnswerCodec.sign(expected,utf8(answer),new DiagnosticAnswerCodec.Signer("provider-diagnostic","provider-key",provider.getPrivate()),catalog::get,DiagnosticAnswerCodec.Options.system());
        }
        CompletionStage<String> respond(NativeDiagnosticProbeAttempt.Request request) {return CompletableFuture.completedFuture(sign(request,job.hostFingerprintHex(),bind.getHostAddress(),port));}
        public void close() throws Exception {host.close().awaitUninterruptibly();host.termination().toCompletableFuture().get(6,TimeUnit.SECONDS);group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();}
    }
    @Test @Timeout(25) void bothFamiliesVerifyOptionalPongsAndActualCleanup() throws Exception {
        for(String address:List.of("127.0.0.1","::1"))try(Fixture fixture=new Fixture(address,20000);var attempt=fixture.attempt()) {
            var result=attempt.run(fixture::respond);
            assertTrue(result.success(),result.toString());assertEquals(NativeDiagnosticProbeAttempt.Reason.COMPLETE,result.reason());
            assertTrue(result.answerVerified());assertTrue(result.transportEstablished());assertTrue(result.authSent());assertTrue(result.pingVerified());assertTrue(result.cleanupComplete());
            assertEquals(fixture.job,result.job());assertEquals(fixture.localPort,result.selectedLocal().getPort());assertEquals(fixture.port,result.selectedRemote().getPort());
            assertEquals(fixture.bind,result.selectedLocal().getAddress());assertEquals(fixture.bind,result.selectedRemote().getAddress());
            assertEquals(2,result.sentFrames());assertEquals(1,result.receivedFrames());assertTrue(result.udp().sentDatagrams()>2);assertEquals(0,result.udp().rejectedDatagrams());assertTrue(result.udp().reservedDatagrams()<=MAX_UDP_SENDS);
            attempt.termination().toCompletableFuture().get(1,TimeUnit.SECONDS);assertThrows(IllegalStateException.class,()->attempt.run(fixture::respond));
            var reports=new ArrayList<NativeDiagnosticHostGate.Result>();await(()->{reports.addAll(fixture.gate.pollResults());return !reports.isEmpty();});
            assertEquals(1,reports.size());assertTrue(reports.get(0).success(),reports.toString());assertTrue(reports.get(0).authenticated());
            assertEquals(result.offerDigestHex(),reports.get(0).offerDigestHex());assertEquals(0,fixture.players.get());assertEquals(0,fixture.gate.stats().liveNativePeers());assertEquals(1,fixture.signalingCalls.get());
        }
    }
    @Test @Timeout(25) void noPingReportsTransportAndAuthSubmissionWithoutClaimingRemoteVerification() throws Exception {
        for(String address:List.of("127.0.0.1","::1"))try(Fixture fixture=new Fixture(address,15000,false);var attempt=fixture.attempt()) {
            var result=attempt.run(fixture::respond);
            assertTrue(result.success(),result.toString());assertTrue(result.answerVerified());assertTrue(result.transportEstablished());
            assertTrue(result.authSent());assertFalse(result.pingVerified());assertFalse(result.job().ping());assertTrue(result.cleanupComplete());
            assertEquals(1,result.sentFrames());assertEquals(217,result.sentBytes());assertEquals(0,result.receivedFrames());assertEquals(0,fixture.players.get());
            await(()->fixture.gate.stats().liveNativePeers()==0);
        }
    }
    @Test @Timeout(25) void providerAnswerFailuresSendNoPeerPacketsOrAdmission() throws Exception {
        for(String mode:List.of("signature","pin","oversize","catalog","withdraw"))try(Fixture fixture=new Fixture("127.0.0.1",15000);var attempt=fixture.attempt()) {
            var result=attempt.run(request->{
                String wire=fixture.sign(request,mode.equals("pin")?"00".repeat(32):fixture.job.hostFingerprintHex(),fixture.bind.getHostAddress(),fixture.port);
                if(mode.equals("signature"))wire=wire.substring(0,wire.length()-4)+(wire.charAt(wire.length()-4)=='A'?'B':'A')+wire.substring(wire.length()-3);
                if(mode.equals("oversize"))wire="x".repeat(DiagnosticAnswerCodec.MAX_WIRE_BYTES+1);
                if(mode.equals("catalog"))fixture.catalog.set(new DiagnosticAnswerCodec.Catalog(fixture.context.providerOrigin(),0,fixture.expiry+5000,fixture.catalog.get().keys()));
                if(mode.equals("withdraw"))fixture.authorized.set(false);
                return CompletableFuture.completedFuture(wire);
            });
            assertFalse(result.success(),mode);assertFalse(result.answerVerified(),mode);assertFalse(result.transportEstablished(),mode);assertTrue(result.cleanupComplete(),mode);assertFalse(result.pingVerified(),mode);
            assertNotNull(result.udp(),mode);assertEquals(0,result.udp().reservedDatagrams(),mode);assertEquals(0,result.udp().sentDatagrams(),mode);assertEquals(0,fixture.host.nativeStats()[0],mode);assertEquals(0,fixture.gate.stats().active(),mode);assertEquals(0,fixture.players.get(),mode);
        }
    }
    @Test @Timeout(20) void cancelledAndUnavailableSignalingKeepOriginalDeadlineAndCleanUp() throws Exception {
        for(boolean cancel:List.of(false,true))try(Fixture fixture=new Fixture("127.0.0.1",cancel?10000:1500);var attempt=fixture.attempt()) {
            var response=new CompletableFuture<String>();var submitted=new CountDownLatch(1);
            var executor=Executors.newSingleThreadExecutor();
            try {
                Future<NativeDiagnosticProbeAttempt.Result> future=executor.submit(()->attempt.run(request->{submitted.countDown();return response;}));
                assertTrue(submitted.await(2,TimeUnit.SECONDS));if(cancel)attempt.close();
                var result=future.get(4,TimeUnit.SECONDS);
                assertFalse(result.success());assertEquals(cancel?NativeDiagnosticProbeAttempt.Reason.CANCELLED:NativeDiagnosticProbeAttempt.Reason.EXPIRED,result.reason());
                assertTrue(result.cleanupComplete());assertTrue(response.isCancelled());assertEquals(0,result.udp()==null?0:result.udp().sentDatagrams());assertEquals(0,fixture.gate.stats().active());
                attempt.termination().toCompletableFuture().get(1,TimeUnit.SECONDS);
            } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
        }
    }
    @Test @Timeout(20) void installedHostWithdrawalAfterAnswerCannotQualify() throws Exception {
        try(Fixture fixture=new Fixture("::1",4000);var attempt=fixture.attempt()) {
            var result=attempt.run(request->{String wire=fixture.respond(request).toCompletableFuture().join();fixture.gate.replacePolicy(new DiagnosticHostPolicy(fixture.context,List.of(),Set.of(),fixture.expiry+10000));return CompletableFuture.completedFuture(wire);});
            assertFalse(result.success());assertTrue(result.answerVerified());assertFalse(result.authSent());assertTrue(result.cleanupComplete());assertFalse(result.pingVerified());assertTrue(result.udp().sentDatagrams()>0);assertEquals(0,fixture.gate.stats().liveNativePeers());assertEquals(0,fixture.players.get());
        }
    }
    @Test @Timeout(20) void signedButWrongActualDtlsIdentityFailsBothFamilies() throws Exception {
        for(String address:List.of("127.0.0.1","::1"))try(Fixture fixture=new Fixture(address,4000)) {
            var expected=new NativeDiagnosticProbeAttempt.Job(fixture.job.context(),fixture.job.attemptIdHex(),fixture.job.target(),"00".repeat(32),fixture.expiry);
            try(var attempt=new NativeDiagnosticProbeAttempt(expected,new InetSocketAddress(fixture.bind,fixture.localPort),fixture.catalog::get,fixture.authorized::get,Clock.system(),true)) {
                var result=attempt.run(request->CompletableFuture.completedFuture(fixture.sign(request,expected.hostFingerprintHex(),fixture.bind.getHostAddress(),fixture.port)));
                assertFalse(result.success(),result.toString());assertTrue(result.answerVerified());assertFalse(result.transportEstablished());assertFalse(result.authSent());assertFalse(result.pingVerified());assertFalse(result.pingVerified());assertTrue(result.cleanupComplete());
                assertTrue(fixture.host.nativeStats()[0]>0);assertEquals(0,fixture.players.get());
            }
        }
    }
    @Test @Timeout(20) void clockCorrectionOrCancellationAfterNativeChecksCannotCreateSuccess() throws Exception {
        for(String change:List.of("forward wall","backward wall with elapsed monotonic","withdraw after checks"))try(Fixture fixture=new Fixture("127.0.0.1",10000)) {
            AtomicLong wall=new AtomicLong(),nanos=new AtomicLong();
            Clock clock=new Clock(()->System.currentTimeMillis()+wall.get(),()->System.nanoTime()+nanos.get());
            BooleanSupplier authorized=()->!change.equals("withdraw after checks")||fixture.gate.stats().active()==0;
            try(var attempt=new NativeDiagnosticProbeAttempt(fixture.job,new InetSocketAddress(fixture.bind,fixture.localPort),fixture.catalog::get,authorized,clock,true)) {
                var result=attempt.run(request->{
                    String answer=fixture.respond(request).toCompletableFuture().join();
                    if(change.equals("forward wall"))wall.set(30000);
                    if(change.startsWith("backward")){wall.set(-30000);nanos.set(TimeUnit.SECONDS.toNanos(30));}
                    return CompletableFuture.completedFuture(answer);
                });
                assertFalse(result.success(),change);assertFalse(result.pingVerified(),change);assertTrue(result.cleanupComplete(),change);
                assertEquals(change.equals("withdraw after checks")?NativeDiagnosticProbeAttempt.Reason.WITHDRAWN:NativeDiagnosticProbeAttempt.Reason.EXPIRED,result.reason(),change);
                assertEquals(change.equals("withdraw after checks"),result.answerVerified(),change);assertEquals(0,fixture.players.get(),change);
                if(!change.equals("withdraw after checks"))assertEquals(0,result.udp().sentDatagrams(),change);
            }
        }
    }
    @Test @Timeout(15) void cancellationClosesNativePeerEvenIfTrustedSignalingViolatesNonblockingContract() throws Exception {
        try(Fixture fixture=new Fixture("127.0.0.1",10000);var attempt=fixture.attempt()) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
            try {
                var result=executor.submit(()->attempt.run(request->{
                    entered.countDown();
                    try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException interrupted) { throw new RuntimeException(interrupted); }
                    return CompletableFuture.completedFuture("late untrusted response");
                }));
                assertTrue(entered.await(2,TimeUnit.SECONDS));attempt.close();
                attempt.termination().toCompletableFuture().get(2,TimeUnit.SECONDS);
                assertFalse(result.isDone()); // The violating callback is still live; native destruction already completed.
                assertEquals(0,fixture.host.nativeStats()[0]);release.countDown();
                var stopped=result.get(2,TimeUnit.SECONDS);assertFalse(stopped.success());assertTrue(stopped.cleanupComplete());
                assertEquals(NativeDiagnosticProbeAttempt.Reason.CANCELLED,stopped.reason());assertNull(stopped.udp());
            } finally {release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
        }
    }
    @Test @Timeout(15) void alreadyCompletedAnswerCannotBypassOriginalHandshakeDeadline() throws Exception {
        try(Fixture fixture=new Fixture("127.0.0.1",60000)) {
            AtomicLong elapsed=new AtomicLong();Clock clock=new Clock(System::currentTimeMillis,()->System.nanoTime()+elapsed.get());
            try(var attempt=new NativeDiagnosticProbeAttempt(fixture.job,new InetSocketAddress(fixture.bind,fixture.localPort),fixture.catalog::get,fixture.authorized::get,clock,true)) {
                var result=attempt.run(request->{
                    String wire=fixture.respond(request).toCompletableFuture().join();
                    elapsed.set(TimeUnit.SECONDS.toNanos(16)); // Stage is already complete when run gets it; wall/job expiry remains valid.
                    return CompletableFuture.completedFuture(wire);
                });
                assertFalse(result.answerVerified(),result.toString());assertFalse(result.success());
                assertEquals(NativeDiagnosticProbeAttempt.Reason.EXPIRED,result.reason());assertTrue(result.cleanupComplete());
                assertEquals(0,result.udp().sentDatagrams());assertEquals(0,fixture.host.nativeStats()[0]);
            }
        }
    }
    @Test @Timeout(15) void interruptedWorkerCannotInstallAlreadyCompletedAnswer() throws Exception {
        try(Fixture fixture=new Fixture("127.0.0.1",15000);var attempt=fixture.attempt()) {
            try {
                var result=attempt.run(request->{String wire=fixture.respond(request).toCompletableFuture().join();Thread.currentThread().interrupt();return CompletableFuture.completedFuture(wire);});
                assertTrue(Thread.interrupted()); // Preserve caller cancellation after bounded cleanup, then clear for JUnit.
                assertFalse(result.answerVerified(),result.toString());assertFalse(result.success());
                assertEquals(NativeDiagnosticProbeAttempt.Reason.CANCELLED,result.reason());assertTrue(result.cleanupComplete());
                assertEquals(0,fixture.host.nativeStats()[0]);
            } finally {Thread.interrupted();}
        }
    }
    @Test void publicConstructionRejectsPrivateReservedMappedWrongFamilyAndUnresolvedTargets() throws Exception {
        var context=new Context("https://provider.example","host",id(),1);long expiry=(System.currentTimeMillis()+10000)/1000*1000;
        for(String address:List.of("127.0.0.1","10.0.0.1","100.64.0.1","169.254.1.1","192.0.2.1","0.0.0.0","224.0.0.1","::1","fc00::1","fe80::1","2001:db8::1","3fff::1")) {
            int family=address.contains(":")?6:4;var endpoint=new DiagnosticHostPolicy.Endpoint(family,DiagnosticAdmissionCodec.address(family,address),12345,1);
            var job=new NativeDiagnosticProbeAttempt.Job(context,id(),endpoint,"00".repeat(32),expiry);
            assertThrows(IllegalArgumentException.class,()->new NativeDiagnosticProbeAttempt(job,new InetSocketAddress(InetAddress.getLoopbackAddress(),12346),()->null,()->true),address);
        }
        assertThrows(IllegalArgumentException.class,()->new DiagnosticHostPolicy.Endpoint(6,"00000000000000000000ffff01020304",12345,1));
        var publicJob=new NativeDiagnosticProbeAttempt.Job(context,id(),new DiagnosticHostPolicy.Endpoint(4,DiagnosticAdmissionCodec.address(4,"1.2.3.4"),12345,1),"00".repeat(32),expiry);
        assertThrows(IllegalArgumentException.class,()->new NativeDiagnosticProbeAttempt(publicJob,InetSocketAddress.createUnresolved("localhost",12346),()->null,()->true));
        assertThrows(IllegalArgumentException.class,()->new NativeDiagnosticProbeAttempt(publicJob,new InetSocketAddress(InetAddress.getByName("::1"),12346),()->null,()->true));
        try(var unused=new NativeDiagnosticProbeAttempt(publicJob,new InetSocketAddress(InetAddress.getByName("127.0.0.1"),12346),()->null,()->true)) {
            unused.close();unused.termination().toCompletableFuture().get(1,TimeUnit.SECONDS);
            var stopped=unused.run(request->{fail("cancelled before run must not signal");return null;});
            assertFalse(stopped.success());assertTrue(stopped.cleanupComplete());assertNull(stopped.udp());
        }
    }
}
