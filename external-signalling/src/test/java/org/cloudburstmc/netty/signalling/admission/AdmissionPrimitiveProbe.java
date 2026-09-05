// SPDX-License-Identifier: MPL-2.0
// Adapted from teamziax/libdatachannel-java at 40f2c329dcb63a762a701b987dc9995d76fd18c7.
// The original file license is preserved; see LICENSES/MPL-2.0.txt.
package org.cloudburstmc.netty.signalling.admission;

import tel.schich.libdatachannel.*;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded feasibility probe, not the production NXS policy implementation. */
public final class AdmissionPrimitiveProbe {
    static final String AUDIENCE="nxs-stateless-host-v1/0123456789abcdef0123456789abcdef";
    static final String SECRET="stateless-fixture-secret-32-bytes-minimum";
    static final String HEADER="NXS1K001";
    static final Base64.Encoder B64=Base64.getEncoder().withoutPadding();
    static final InetAddress LOOPBACK=InetAddress.getLoopbackAddress();
    static final int PORT=49184;
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String field(String sdp,String name) {
        return sdp.lines().filter(x->x.startsWith("a="+name+":")).findFirst().orElseThrow().substring(name.length()+3).trim();
    }
    static byte[] hmac(String algorithm,byte[] key,byte[] input) throws Exception {
        Mac mac=Mac.getInstance(algorithm); mac.init(new SecretKeySpec(key,algorithm)); return mac.doFinal(input);
    }
    static byte[] encryptionKey() throws Exception {
        return hmac("HmacSHA256",bytes(SECRET),bytes("nxs-stateless-aead-v1\0"+AUDIENCE));
    }
    static String password(String token) throws Exception {
        return B64.encodeToString(Arrays.copyOf(hmac("HmacSHA256",bytes(SECRET),bytes("nxs-stateless-ice-v1\0"+AUDIENCE+"\0"+token)),24));
    }
    static byte[] aad(String clientUfrag) { return bytes("nxs-stateless-admission-v1\0"+HEADER+"\0"+AUDIENCE+"\0"+clientUfrag); }
    // Signalling side only. Host receives NONE of these arguments out of band.
    static String mint(String offer,int passwordLength,boolean wrongFingerprint) throws Exception {
        String pwd=field(offer,"ice-pwd");
        check(pwd.length()==passwordLength,"client password length");
        byte[] fingerprint=HexFormat.of().parseHex(field(offer,"fingerprint").substring(8).replace(":",""));
        if(wrongFingerprint) fingerprint[0]^=1;
        ByteBuffer plain=ByteBuffer.allocate(67+pwd.length());
        plain.putInt((int)(System.currentTimeMillis()/1000+30)).put(fingerprint).putShort((short)5000).putInt(262144);
        plain.put(new byte[16]).putLong(42).put((byte)pwd.length()).put(bytes(pwd));
        byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(encryptionKey(),"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD(aad(field(offer,"ice-ufrag")));
        byte[] encrypted=cipher.doFinal(plain.array()); Arrays.fill(plain.array(),(byte)0);
        return HEADER+B64.encodeToString(ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array());
    }
    record Admission(String token,String clientUfrag,String clientPassword,String fingerprint,int sctp,int max,String tuple,long firstValidNanos) {
        @Override public String toString() { return "Admission[redacted]"; }
        String offer() {
            return "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\n"+
                "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=setup:actpass\r\n"+
                "a=ice-ufrag:"+clientUfrag+"\r\na=ice-pwd:"+clientPassword+"\r\na=fingerprint:sha-256 "+fingerprint+
                "\r\na=sctp-port:"+sctp+"\r\na=max-message-size:"+max+"\r\n";
        }
    }
    // Host uses only the packet plus background key/profile. No offer or client cache input.
    static Admission validate(byte[] packet,String tuple) throws Exception {
        if(packet.length<20 || packet.length>2048) return null;
        ByteBuffer b=ByteBuffer.wrap(packet);
        if(b.getShort(0)!=1 || b.getInt(4)!=0x2112a442 || Short.toUnsignedInt(b.getShort(2))+20!=packet.length) return null;
        String username=null; int integrity=-1;
        for(int i=20;i<packet.length;) {
            if(i+4>packet.length) return null;
            int type=Short.toUnsignedInt(b.getShort(i)), len=Short.toUnsignedInt(b.getShort(i+2));
            if(i+4+len>packet.length) return null;
            if(type==6) { if(username!=null || integrity!=-1) return null; username=new String(packet,i+4,len,StandardCharsets.US_ASCII); }
            if(type==8) { if(integrity!=-1 || len!=20 || username==null) return null; integrity=i; }
            i+=4+((len+3)&~3); if(i>packet.length) return null;
        }
        if(username==null || integrity<0) return null;
        String[] names=username.split(":",-1);
        if(names.length!=2 || names[0].length()>256 || !names[0].startsWith(HEADER) || !names[1].matches("[A-Za-z0-9+/]{4,256}")) return null;
        byte[] envelope=Base64.getDecoder().decode(names[0].substring(8));
        if(envelope.length<117 || !B64.encodeToString(envelope).equals(names[0].substring(8))) return null;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(encryptionKey(),"AES"),new GCMParameterSpec(128,Arrays.copyOf(envelope,12)));
        cipher.updateAAD(aad(names[1]));
        byte[] raw=cipher.doFinal(Arrays.copyOfRange(envelope,12,envelope.length));
        try {
            ByteBuffer plain=ByteBuffer.wrap(raw);
            long expiry=Integer.toUnsignedLong(plain.getInt())*1000;
            if(expiry<=System.currentTimeMillis() || expiry>System.currentTimeMillis()+60000) return null;
            byte[] fp=new byte[32]; plain.get(fp);
            int sctp=Short.toUnsignedInt(plain.getShort()), max=plain.getInt();
            plain.position(66); int len=Byte.toUnsignedInt(plain.get());
            if(len<22 || len>91 || plain.remaining()!=len || sctp==0 || max<1 || max>262144) return null;
            byte[] pwd=new byte[len];plain.get(pwd);
            String remotePassword=new String(pwd,StandardCharsets.US_ASCII);Arrays.fill(pwd,(byte)0);
            if(!remotePassword.matches("[A-Za-z0-9+/]{22,91}")) return null;
            byte[] signed=Arrays.copyOf(packet,integrity);
            ByteBuffer.wrap(signed).putShort(2,(short)(integrity+24-20));
            byte[] expected=hmac("HmacSHA1",bytes(password(names[0])),signed);
            if(!MessageDigest.isEqual(expected,Arrays.copyOfRange(packet,integrity+4,integrity+24))) return null;
            return new Admission(names[0],names[1],remotePassword,HexFormat.ofDelimiter(":").withUpperCase().formatHex(fp),sctp,max,tuple,System.nanoTime());
        } finally {Arrays.fill(raw,(byte)0);}
    }
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path certificate=Path.of(args[0]), key=Path.of(args[1]);
        byte[] der;
        try(var input=Files.newInputStream(certificate)) { der=CertificateFactory.getInstance("X.509").generateCertificate(input).getEncoded(); }
        String hostFingerprint=HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
        for(int passwordLength:new int[]{24,32,91}) run(certificate,key,hostFingerprint,passwordLength,false);
        run(certificate,key,hostFingerprint,24,true);
    }
    static void run(Path certificate,Path key,String hostFingerprint,int passwordLength,boolean wrongFingerprint) throws Exception {
        ArrayBlockingQueue<Admission> work=new ArrayBlockingQueue<>(4);
        Set<String> approved=ConcurrentHashMap.newKeySet(), claimed=ConcurrentHashMap.newKeySet();
        AtomicInteger rejected=new AtomicInteger(),created=new AtomicInteger(),rawPackets=new AtomicInteger();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        AtomicReference<byte[]> initialPacket=new AtomicReference<>();
        AtomicInteger initialPort=new AtomicInteger();
        List<PeerConnection> hosts=new ArrayList<>();
        CountDownLatch messages=new CountDownLatch(2), opened=new CountDownLatch(2);
        AtomicInteger channelMask=new AtomicInteger(), callbackCloseGuards=new AtomicInteger();
        CountDownLatch hostFailed=new CountDownLatch(1);
        try(RawUdpMuxListener mux=new RawUdpMuxListener(LOOPBACK,PORT,(packet,address,port)->{
            rawPackets.incrementAndGet(); String tuple=address+":"+port;
            if(approved.contains(tuple)) return true;
            try {
                Admission admission=validate(packet,tuple);
                if(admission==null) {rejected.incrementAndGet();return false;}
                if(claimed.add(admission.token())) {
                    initialPacket.set(packet); initialPort.set(port);
                    check(work.offer(admission),"bounded creation queue");
                }
            } catch(Exception error) {rejected.incrementAndGet();}
            return false;
        });PeerConnection client=PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true).withBindAddress(LOOPBACK))) {
            long baselineNativeAttempts=PeerConnection.nativeCreationAttempts();
            check(mux.stats()[2]==0,"host has zero agents before any client packet");
            try(DatagramSocket invalid=new DatagramSocket()) {
                byte[] noise=new byte[40];invalid.send(new DatagramPacket(noise,noise.length,LOOPBACK,PORT));
                for(int i=0;i<100 && rejected.get()==0;i++) Thread.sleep(5);
                check(rejected.get()>0 && mux.stats()[2]==0 && mux.stats()[3]==0,"invalid datagram created no native state");
            }
            List<DataChannel> clientChannels=new ArrayList<>();
            for(int channel=0;channel<2;channel++) {
                String label=channel==0?"ReliableDataChannel":"UnreliableDataChannel";
                var init=DataChannelInitSettings.DEFAULT.withReliability(new DataChannelReliability(channel==1,channel==1,0,0));
                var dc=client.createDataChannel(label,init);clientChannels.add(dc);
                dc.onOpen.register(d->{
                    try { client.closeAndAwait(java.time.Duration.ofMillis(1)); failure.set(new AssertionError("teardown wait must reject callback context")); }
                    catch(IllegalStateException expected) { callbackCloseGuards.incrementAndGet(); }
                    opened.countDown();ByteBuffer message=ByteBuffer.allocateDirect(2);message.put((byte)0).put((byte)(label.startsWith("Reliable")?1:2)).flip();d.sendMessage(message);});
            }
            client.setLocalDescription("offer","clientFixtureUf","p".repeat(passwordLength));
            String token=mint(client.localDescription(),passwordLength,wrongFingerprint);
            check(token.length()==8+(int)Math.ceil((95+passwordLength)*4.0/3),"token byte budget");
            // NXS-generated answer: never obtained from a native server peer.
            String answer="v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\n"+
                "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=setup:active\r\n"+
                "a=ice-ufrag:"+token+"\r\na=ice-pwd:"+password(token)+"\r\na=fingerprint:sha-256 "+hostFingerprint+
                "\r\na=sctp-port:5000\r\na=max-message-size:262144\r\na=candidate:1 1 UDP 2130706431 127.0.0.1 "+PORT+" typ host\r\na=end-of-candidates\r\n";
            client.setRemoteDescription(answer,SessionDescriptionType.ANSWER);
            Admission admitted=work.poll(10,TimeUnit.SECONDS);check(admitted!=null,"valid raw STUN reaches endpoint without control delivery");
            check(mux.stats()[2]==0 && PeerConnection.nativeCreationAttempts()==baselineNativeAttempts,"token validation precedes all native creation attempts");
            PeerConnection host=PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true).withBindAddress(LOOPBACK)
                .withEnableIceUdpMux(true).withPortRangeBegin((short)PORT).withPortRangeEnd((short)PORT),Runnable::run,certificate,key);
            hosts.add(host);created.incrementAndGet();
            check(PeerConnection.nativeCreationAttempts()==baselineNativeAttempts+1,"exactly one native creation attempt");
            check(System.nanoTime()>admitted.firstValidNanos(),"monotonic validation before creation");
            host.onStateChange.register((p,state)->{if(state==PeerState.RTC_FAILED) hostFailed.countDown();});
            host.onDataChannel.register((p,dc)->{
                String label=dc.label();int bit=label.equals("ReliableDataChannel")?1:label.equals("UnreliableDataChannel")?2:0;
                if(bit==0){failure.set(new AssertionError("unexpected label"));return;}
                channelMask.getAndUpdate(mask->mask|bit);
                dc.onMessage.register(DataChannelCallback.Message.handleBinary((d,buffer)->{
                    try {check(buffer.remaining()==2 && buffer.get()==0 && buffer.get()==bit,"channel identity and payload");messages.countDown();}
                    catch(Throwable error){failure.set(error);}
                }));
            });
            host.setRemoteDescription(admitted.offer(),SessionDescriptionType.OFFER);
            host.setLocalDescription("answer",admitted.token(),password(admitted.token()));
            check(field(host.localDescription(),"fingerprint").equals("sha-256 "+hostFingerprint),"published native certificate identity");
            check(field(host.localDescription(),"ice-ufrag").equals(token),"native did not truncate token");
            approved.add(admitted.tuple());
            mux.replay(initialPacket.getAndSet(null),LOOPBACK,initialPort.get());
            if(wrongFingerprint) {
                check(hostFailed.await(15,TimeUnit.SECONDS),"DTLS rejects authenticated token with wrong client fingerprint");
                check(channelMask.get()==0 && opened.getCount()==2,"wrong certificate opens no channels");
                System.out.println("native-spike PASS wrongClientFingerprint=dtls-rejected channels=0 perJoinControl=0");
                for(PeerConnection peer:hosts) check(peer.closeAndAwait(java.time.Duration.ofSeconds(5)),"native teardown completes before releasing capacity");hosts.clear();
                return;
            }
            check(opened.await(10,TimeUnit.SECONDS),"both client channels open");
            check(messages.await(10,TimeUnit.SECONDS),"both channels deliver distinct binary messages");
            check(failure.get()==null && callbackCloseGuards.get()==2,"native callbacks completed without failure and cannot wait on themselves");
            check(created.get()==1 && work.isEmpty() && channelMask.get()==3,"one lazy peer and both channels");
            long[] stats=mux.stats();check(stats[2]==1 && stats[3]==1,"one fixed-port agent and tuple");
            System.out.println("native-spike PASS ufragChars="+token.length()+" passwordBytes="+passwordLength+" hostPeers="+created.get()+" rawPackets="+rawPackets.get()+" channels=3 perJoinControl=0");
            for(PeerConnection peer:hosts) check(peer.closeAndAwait(java.time.Duration.ofSeconds(5)),"native teardown completes before releasing capacity");hosts.clear();
        } finally {for(PeerConnection peer:hosts) check(peer.closeAndAwait(java.time.Duration.ofSeconds(5)),"native teardown completes before releasing capacity");}
    }
}
