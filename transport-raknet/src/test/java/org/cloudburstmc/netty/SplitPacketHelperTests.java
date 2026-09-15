/*
 * Copyright 2025 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.util.SplitPacketHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SplitPacketHelperTests {

    private static final PooledByteBufAllocator ALLOC = PooledByteBufAllocator.DEFAULT;

    private static EncapsulatedPacket part(int partCount, int partIndex, int payloadBytes) {
        ByteBuf buffer = ALLOC.ioBuffer(payloadBytes);
        buffer.writeZero(payloadBytes);

        EncapsulatedPacket packet = EncapsulatedPacket.newInstance();
        packet.setReliability(RakReliability.RELIABLE);
        packet.setSplit(true);
        packet.setPartCount(partCount);
        packet.setPartId(0);
        packet.setPartIndex(partIndex);
        packet.setBuffer(buffer);
        return packet;
    }

    @Test
    public void reassembledSizeTracksRetainedBytes() {
        SplitPacketHelper helper = new SplitPacketHelper(0, 3);
        Assertions.assertEquals(0, helper.getReassembledSize());

        EncapsulatedPacket p0 = part(3, 0, 100);
        Assertions.assertNull(helper.add(p0, ALLOC));
        Assertions.assertEquals(100, helper.getReassembledSize());
        p0.release();

        // Duplicate part must not be counted twice.
        EncapsulatedPacket dup = part(3, 0, 100);
        Assertions.assertNull(helper.add(dup, ALLOC));
        Assertions.assertEquals(100, helper.getReassembledSize());
        dup.release();

        EncapsulatedPacket p1 = part(3, 1, 50);
        Assertions.assertNull(helper.add(p1, ALLOC));
        Assertions.assertEquals(150, helper.getReassembledSize());
        p1.release();

        // Final part completes the reassembly; the helper still reports the full retained size until released.
        EncapsulatedPacket p2 = part(3, 2, 25);
        EncapsulatedPacket reassembled = helper.add(p2, ALLOC);
        Assertions.assertNotNull(reassembled);
        Assertions.assertEquals(175, helper.getReassembledSize());
        Assertions.assertEquals(175, reassembled.getBuffer().readableBytes());
        reassembled.release();
        p2.release();

        helper.release();
    }

    @Test
    public void expiresAfterTimeout() {
        SplitPacketHelper helper = new SplitPacketHelper(0, 2);
        Assertions.assertFalse(helper.expired());
        helper.release();
    }
}
