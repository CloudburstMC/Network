package org.cloudburstmc.netty.channel.raknet.packet;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.netty.util.IntRange;

import static org.cloudburstmc.netty.RakNetConstants.*;

public class AcknowledgedPacket implements RakCodecPacket {

    private boolean nack;
    private IntRange[] entries;

    public AcknowledgedPacket() {
    }

    @Override
    public void encode(ByteBuf buffer) {
        buffer.writeByte(FLAG_VALID | (this.nack ? FLAG_NACK : FLAG_ACK));
        buffer.writeShort(this.entries.length);

        for (IntRange range : this.entries) {
            if (range.start == range.end) {
                buffer.writeBoolean(true);
                buffer.writeMediumLE(range.start);
            } else {
                buffer.writeBoolean(false);
                buffer.writeMediumLE(range.start);
                buffer.writeMediumLE(range.end);
            }
        }
    }

    @Override
    public void decode(ByteBuf buffer) {
        this.nack = (buffer.readByte() & FLAG_NACK) != 0;
        this.entries = new IntRange[buffer.readUnsignedShort()];

        for (int i = 0; i < this.entries.length; i++) {
            boolean singleton = buffer.readBoolean();
            int start = buffer.readUnsignedMediumLE();

            // We don't need the upper limit if it's a singleton
            int end = singleton ? start : buffer.readMediumLE();
            this.entries[i] = new IntRange(start, end);
        }
    }

    public void setEntries(IntRange[] entries) {
        this.entries = entries;
    }

    public IntRange[] getEntries() {
        return this.entries;
    }

    public void setNack(boolean nack) {
        this.nack = nack;
    }

    public boolean isNack() {
        return this.nack;
    }
}

