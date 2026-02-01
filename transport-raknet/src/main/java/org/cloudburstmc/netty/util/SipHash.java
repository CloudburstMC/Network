/*
 * Copyright 2025 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

/* 
 * SipHash implementation based on the reference SipHash-2-4 implementation at 
 * https://github.com/veorq/SipHash, which is multi-licensed under:
 * CC0-1.0: https://github.com/veorq/SipHash/blob/master/LICENCE_CC0
 * MIT: https://github.com/veorq/SipHash/blob/master/LICENSE_MIT
 * Apache-2.0: https://github.com/veorq/SipHash/blob/master/LICENSE_A2LLVM
 */

package org.cloudburstmc.netty.util;

import org.cloudburstmc.netty.channel.raknet.config.RakServerCookieMode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;

public class SipHash {

    private final byte[] secret;
    
    private final Object lock = new Object();
    private volatile Cache cache = new Cache(-1, null, -1, null);

    public SipHash(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        if (key.length == 0) {
            throw new IllegalArgumentException("Key cannot be empty");
        }
        
        this.secret = key.clone();
    }

    private long hash(byte[] data, int length, long k0, long k1) {
        long v0 = 0x736f6d6570736575L ^ k0;
        long v1 = 0x646f72616e646f6dL ^ k1;
        long v2 = 0x6c7967656e657261L ^ k0;
        long v3 = 0x7465646279746573L ^ k1;

        int left = length & 7;
        int i = 0;

        while (i < (length - left)) {
            long m = 0;
            for (int j = 0; j < 8; j++) {
                m |= ((long) (data[i + j] & 0xFF)) << (j * 8);
            }
            
            // SIPROUND x 2
            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2; v0 += v3;
            v3 = Long.rotateLeft(v3, 21); v3 ^= v0; v2 += v1; v1 = Long.rotateLeft(v1, 17);
            v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
            
            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2; v0 += v3;
            v3 = Long.rotateLeft(v3, 21); v3 ^= v0; v2 += v1; v1 = Long.rotateLeft(v1, 17);
            v1 ^= v2; v2 = Long.rotateLeft(v2, 32);

            v0 ^= m;
            v3 ^= m;
            i += 8;
        }

        long b = ((long) length) << 56;
        switch (left) {
            case 7: b |= ((long) (data[i + 6] & 0xFF)) << 48;
            case 6: b |= ((long) (data[i + 5] & 0xFF)) << 40;
            case 5: b |= ((long) (data[i + 4] & 0xFF)) << 32;
            case 4: b |= ((long) (data[i + 3] & 0xFF)) << 24;
            case 3: b |= ((long) (data[i + 2] & 0xFF)) << 16;
            case 2: b |= ((long) (data[i + 1] & 0xFF)) << 8;
            case 1: b |= ((long) (data[i] & 0xFF));
            case 0: break;
        }

        v3 ^= b;

        // SIPROUND x 2
        v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
        v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2; v0 += v3;
        v3 = Long.rotateLeft(v3, 21); v3 ^= v0; v2 += v1; v1 = Long.rotateLeft(v1, 17);
        v1 ^= v2; v2 = Long.rotateLeft(v2, 32);

        v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
        v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2; v0 += v3;
        v3 = Long.rotateLeft(v3, 21); v3 ^= v0; v2 += v1; v1 = Long.rotateLeft(v1, 17);
        v1 ^= v2; v2 = Long.rotateLeft(v2, 32);

        v0 ^= b;
        v2 ^= 0xff;

        // SIPROUND x 4
        for (int d = 0; d < 4; d++) {
            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2; v0 += v3;
            v3 = Long.rotateLeft(v3, 21); v3 ^= v0; v2 += v1; v1 = Long.rotateLeft(v1, 17);
            v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
        }

        return v0 ^ v1 ^ v2 ^ v3;
    }

    public int generateStatelessCookie(InetSocketAddress sender, int protocolVersion) {
        long now = now();
        // 4 bits timestamp (16 minutes cycle)
        long timestampMinutes = (now / 60000) & 0x0F;
        long epoch = (now / 1000) / 600;
        
        SipHashKey keys = getKeys(epoch);
        
        // High bits: Timestamp
        // Low bits: Protocol Version (Mapped 1-16 -> 0-15)
        int proto = (protocolVersion - 1) & 0x0F;
        int combinedByte = (int) ((timestampMinutes << 4) | proto);

        long signature = computeSignature(sender, combinedByte, keys);
        
        // Cookie = [Signature (24 bits) | Timestamp (4 bits) | Protocol (4 bits)]
        return (int) ((signature << 8) | combinedByte);
    }

    private long computeSignature(InetSocketAddress sender, long timestamp, SipHashKey keys) {        
        byte[] addressBytes = sender.getAddress().getAddress();
        int port = sender.getPort();
        
        // Data = [IP (4/16) | Port (2) | Timestamp (1)]
        byte[] data = new byte[addressBytes.length + 2 + 1];
        System.arraycopy(addressBytes, 0, data, 0, addressBytes.length);
        int pos = addressBytes.length;
        data[pos++] = (byte) (port >>> 8);
        data[pos++] = (byte) (port);
        data[pos] = (byte) timestamp;

        long hash = this.hash(data, data.length, keys.k0, keys.k1);
        return hash & 0xFFFFFF; // Truncate to 24 bits
    }

    public boolean validateCookie(int cookie, InetSocketAddress sender, RakServerCookieMode mode) {
        if (mode == RakServerCookieMode.OFF) {
            return true;
        }

        int combinedByte = cookie & 0xFF;
        int timestamp = (combinedByte >>> 4) & 0x0F;
        int receivedSignature = (cookie >>> 8) & 0xFFFFFF;

        // Verify timestamp (All modes except OFF)
        long now = now();
        long currentMinutes = (now / 60000) & 0x0F;
        long diff = (currentMinutes - timestamp) & 0x0F; // Wrap-around 4 bits
        
        // (0 = current, 1 = previous, etc.)
        // If diff is small positive, it's recent past.
        if (diff > 1) { // 2 minutes
             return false;
        }

        if (mode == RakServerCookieMode.OFFLOADED) {
            return true; // Ignore signature
        }

        // Reconstruct epoch from the timestamp in the cookie
        // We calculate the absolute time when the cookie was likely generated
        long approximateOriginalTime = now - (diff * 60000);
        long epoch = (approximateOriginalTime / 1000) / 600;

        // ACTIVE or OFFLOADED_PSK
        SipHashKey keys = getKeys(epoch);
        long expectedSignature = computeSignature(sender, combinedByte, keys);
        return receivedSignature == expectedSignature;
    }

    public static int getProtocolVersion(int cookie) {
        // Low 4 bits + 1
        return (cookie & 0x0F) + 1;
    }

    protected long now() {
        return System.currentTimeMillis();
    }

    private SipHashKey getKeys(long epoch) {
        Cache current = this.cache;
        if (current.epoch1 == epoch) return current.key1;
        if (current.epoch2 == epoch) return current.key2;

        synchronized (lock) {
            current = this.cache; // Double-checked locking
            if (current.epoch1 == epoch) return current.key1;
            if (current.epoch2 == epoch) return current.key2;
            
            SipHashKey keys = computeKeys(epoch);
            
            // New key becomes primary. Old primary becomes secondary.
            this.cache = new Cache(epoch, keys, current.epoch1, current.key1);
            
            return keys;
        }
    }

    private SipHashKey computeKeys(long epoch) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            
            byte[] epochBytes = new byte[8];
            for(int i = 7; i >= 0; i--) {
                epochBytes[i] = (byte)(epoch & 0xFF);
                epoch >>= 8;
            }
            
            byte[] digest = mac.doFinal(epochBytes);

            long k0 = 0;
            long k1 = 0;

            for (int i = 0; i < 8; i++) {
                k0 |= ((long) (digest[i] & 0xFF)) << (i * 8);
                k1 |= ((long) (digest[i + 8] & 0xFF)) << (i * 8);
            }
            
            return new SipHashKey(k0, k1);
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive SipHash keys", e);
        }
    }

    /**
     * Immutable container for caching keys. 
     */
    private static class Cache {
        final long epoch1;
        final SipHashKey key1;
        final long epoch2;
        final SipHashKey key2;
        
        Cache(long epoch1, SipHashKey key1, long epoch2, SipHashKey key2) {
            this.epoch1 = epoch1;
            this.key1 = key1;
            this.epoch2 = epoch2;
            this.key2 = key2;
        }
    }

    private static class SipHashKey {
        final long k0;
        final long k1;

        SipHashKey(long k0, long k1) {
            this.k0 = k0;
            this.k1 = k1;
        }
    }
}
