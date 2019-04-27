package com.nukkitx.network.raknet;

public class EncapsulatedBinaryHeap {
    private EncapsulatedPacket[] heap;
    private int size;

    public EncapsulatedBinaryHeap(int initialCapacity) {
        this.heap = new EncapsulatedPacket[initialCapacity];
    }

    private void resize(int capacity) {
        EncapsulatedPacket[] newHeap = new EncapsulatedPacket[capacity];
        System.arraycopy(this.heap, 0, newHeap, 0, this.size);
        this.heap = newHeap;
    }

    public void insert(EncapsulatedPacket packet) {
        if (this.size >= this.heap.length) {
            this.resize(this.size << 1);
        }

        int hole = ++this.size;
        int pred = hole >> 1;
        EncapsulatedPacket predKey = this.heap[pred];

        while (predKey.orderingIndex > packet.orderingIndex) {
            this.heap[hole] = predKey;
            hole = pred;
            pred >>= 1;
            predKey = this.heap[pred];
        }

        this.heap[hole] = packet;
    }

    public EncapsulatedPacket peek() {
        return this.heap[0];
    }

    public void remove() {
        int hole = 1;
        int succ = 2;
        int sz = this.size;
        while (succ < sz) {
            EncapsulatedPacket val1 = this.heap[succ];
            EncapsulatedPacket val2 = this.heap[succ + 1];
            if (val1.orderingIndex > val2.orderingIndex) {
                succ++;
                this.heap[hole] = val2;
            } else {
                this.heap[hole] = val1;
            }
            hole = succ;
            succ <<= 1;
        }

        // bubble up rightmost element
        EncapsulatedPacket bubble = this.heap[sz];
        int pred = hole >> 1;
        while (this.heap[pred].orderingIndex > bubble.orderingIndex) { // must terminate since min at root
            this.heap[hole] = this.heap[pred];
            hole = pred;
            pred >>= 1;
        }

        // finally move data to hole
        this.heap[hole] = bubble;

        this.heap[size] = null; // mark as deleted
        size = sz - 1;

        if ((this.size << 2) < this.heap.length && this.size > 4) {
            this.resize(this.size << 1);
        }
    }
}
