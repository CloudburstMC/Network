package com.nukkitx.network.raknet.util;

import com.nukkitx.network.raknet.RakNetUtils;

public class BitQueue {

    private int capacity;
    private long[] queue;
    private int head;
    private int tail;

    public BitQueue(int capacity) {
        this.capacity = RakNetUtils.powerOfTwoCeiling(capacity);
        this.queue = this.newArray(this.capacity + 1);
        this.head = 0;
        this.tail = 0;
    }

    public void resize(int newCapacity) {
        int size = this.size();
        if (newCapacity < size)
            throw new IndexOutOfBoundsException("Resizing would lose data");
        newCapacity++;
        if (newCapacity == this.capacity)
            return;
        long[] newQueue = newArray(newCapacity);

        if ((this.tail & 63) == 0) {
            if (this.head > this.tail) {
                int srcPos = this.tail >> 6;
                int length = (this.head - this.tail + 63) >> 6;
                System.arraycopy(this.queue, srcPos, newQueue, 0, length);
            } else if (this.head < this.tail) {
                int srcPos = this.tail >> 6;
                int adjustedPos = ((this.capacity << 6) - this.tail + 63) >> 6;
                int length = (this.head + 63) >> 6;
                System.arraycopy(this.queue, srcPos, newQueue, 0, adjustedPos);
                System.arraycopy(this.queue, srcPos, newQueue, adjustedPos, length);
            }
        } else {
            int tailBits = (this.tail & 63);
            int tailIdx = this.tail >> 6;
            int by2 = (tailIdx + 1) & (this.capacity - 1);
            long mask;
            long bit1;
            long bit2;

            int cursor = 0;
            while (cursor < size) {
                mask = ((1L << tailBits) - 1) & 0xFF;
                bit1 = ((this.queue[tailIdx] & (~mask & 0xFF)) >>> tailBits);
                bit2 = (this.queue[by2] << (64 - tailBits));
                newQueue[cursor >> 6] = bit1 | bit2;

                cursor += 8;
                tailIdx = (tailIdx + 1) & (this.capacity - 1);
                by2 = (by2 + 1) & (this.capacity - 1);
            }

            this.tail = 0;
            this.head = size;
        }

        this.capacity = newCapacity;
        this.queue = newQueue;
        this.head = 0;
        this.tail = size;
    }

    private long[] newArray(int capacity) {
        return new long[(capacity + 63) >> 6];
    }

    public void add(boolean val) {
        if (((this.head + 1) & ((this.queue.length << 6) - 1)) == this.tail) {
            this.resize(this.queue.length << 7);
        }

        int idx = this.head >> 6;
        long mask = 1L << ((this.tail) & 63);
        this.queue[idx] ^= ((val ? 0xFF : 0x00) ^ this.queue[idx]) & mask;
        this.head = (this.head + 1) & ((this.queue.length << 6) - 1);
    }

    public void set(int i, boolean val) {
        if (i >= this.size() || i < 0) {
            return;
        }

        int idx = (this.tail + i) & ((this.queue.length << 6) - 1);
        int arrIdx = idx >> 6;
        long mask = 1L << (idx & 63);
        this.queue[arrIdx] ^= ((val ? 0xFF : 0x00) ^ this.queue[arrIdx]) & mask;
    }

    public boolean poll() {
        if (this.head == this.tail) {
            return false;
        }
        int arrIdx = this.tail >> 6;
        long mask = 1L << ((this.tail) & 63);
        this.tail = (this.tail + 1) & ((this.queue.length << 6) - 1);
        return (this.queue[arrIdx] & mask) != 0;
    }

    public boolean peek() {
        if (this.head == this.tail) {
            return false;
        }
        int arrIdx = this.tail >> 6;
        long mask = 1L << ((this.tail) & 63);
        return (this.queue[arrIdx] & mask) != 0;
    }

    public boolean get(int i) {
        int size = this.size();
        if (i < 0 || i >= size) {
            final String msg = "Index " + i + ", queue size " + size;
            throw new IndexOutOfBoundsException(msg);
        }
        int index = (this.tail + i) & ((this.queue.length << 6) - 1);
        int arrIndex = index >> 6;
        long mask = 1L << (index & 63);
        return (this.queue[arrIndex] & mask) != 0;
    }

    public int size() {
        if (this.head > this.tail) {
            return (this.head - this.tail);
        } else if (this.head < this.tail) {
            return ((this.queue.length << 6) - (this.tail - this.head));
        } else {
            return 0;
        }
    }

    public boolean isEmpty() {
        return (this.head == this.tail);
    }
}
