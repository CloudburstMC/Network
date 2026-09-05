package org.cloudburstmc.netty.signalling.admission;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Test signalling source. Offer and issued token are NEVER delivered to the host. */
final class TestSignallingProvider {
    static final String AUDIENCE = "sig_fixture/gs_one/test_boot_001", SECRET = "stateless-fixture-secret-32-bytes-minimum";
    record Answer(String sdp, String token, String password) { @Override public String toString() { return "Answer[redacted]"; } }
    static String field(String sdp, String name) { return sdp.lines().filter(s -> s.startsWith("a=" + name + ":")).findFirst().orElseThrow().substring(name.length() + 3).trim(); }
    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static byte[] hmac(byte[] key, String data) throws Exception { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(utf8(data)); }
    static Answer answer(String offer, String fingerprint, int port, long expiry, String audience, boolean wrongClientFingerprint) throws Exception {
        String ufrag = field(offer, "ice-ufrag"), pwd = field(offer, "ice-pwd");
        byte[] fp = HexFormat.of().parseHex(field(offer, "fingerprint").substring(8).replace(":", ""));
        if (wrongClientFingerprint) fp[0] ^= 1;
        ByteBuffer claims = ByteBuffer.allocate(67 + pwd.length());
        claims.putInt((int)(expiry / 1000)).put(fp).putShort((short)5000).putInt(262144).put(new byte[16]).putLong(42).put((byte)pwd.length()).put(utf8(pwd));
        byte[] nonce = new byte[12];new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(hmac(utf8(SECRET),"nxs-stateless-aead-v1\0"+audience),"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD(utf8("nxs-stateless-admission-v1\0NXS1K001\0"+audience+"\0"+ufrag));
        byte[] sealed = cipher.doFinal(claims.array());Arrays.fill(claims.array(),(byte)0);
        var base64 = Base64.getEncoder().withoutPadding();
        String token = "NXS1K001" + base64.encodeToString(ByteBuffer.allocate(12+sealed.length).put(nonce).put(sealed).array());
        String password = base64.encodeToString(Arrays.copyOf(hmac(utf8(SECRET),"nxs-stateless-ice-v1\0"+audience+"\0"+token),24));
        String sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\n" +
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=setup:active\r\n" +
            "a=ice-ufrag:"+token+"\r\na=ice-pwd:"+password+"\r\na=fingerprint:"+fingerprint+"\r\na=sctp-port:5000\r\na=max-message-size:262144\r\n"+
            "a=candidate:1 1 UDP 2130706431 127.0.0.1 "+port+" typ host\r\na=end-of-candidates\r\n";
        return new Answer(sdp,token,password);
    }
}
