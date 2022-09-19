package org.cloudburstmc.netty.util;

import io.netty.util.AbstractReferenceCounted;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.ObjectUtil;

import java.util.Arrays;
import java.util.NoSuchElementException;

public class FastBinaryMinHeap<E> extends AbstractReferenceCounted {

    private static final Entry INFIMUM = new Entry(Long.MAX_VALUE);
    private static final Entry SUPREMUM = new Entry(Long.MIN_VALUE);
    private static final ObjectPool<Entry> RECYCLER = ObjectPool.newPool(Entry::new);
    private int size;

    public FastBinaryMinHeap() {
        this(8);
    }

    private Entry[] heap;

    public FastBinaryMinHeap(int initialCapacity) {
        this.heap = new Entry[++initialCapacity];
        Arrays.fill(this.heap, INFIMUM);
        this.heap[0] = SUPREMUM;
    }

    private static Entry newEntry(Object element, long weight) {
        Entry entry = RECYCLER.get();
        entry.element = element;
        entry.weight = weight;

        return entry;
    }

    private void resize(int capacity) {
        int adjustedSize = this.size + 1;
        int copyLength = Math.min(this.heap.length, adjustedSize);
        Entry[] newHeap = new Entry[capacity];
        System.arraycopy(this.heap, 0, newHeap, 0, copyLength);
        if (capacity > adjustedSize) {
            Arrays.fill(newHeap, adjustedSize, capacity, INFIMUM);
        }
        this.heap = newHeap;
    }

    public void insert(long weight, E element) {
        ObjectUtil.checkNotNull(element, "element");
        this.ensureCapacity(this.size + 1);
        this.insert0(weight, element);
    }

    private void ensureCapacity(int size) {
        // +1 for infimum
        if (size + 1 >= this.heap.length) {
            this.resize(RakUtils.powerOfTwoCeiling(size + 1));
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        Entry entry = this.heap[1];
        return entry != null ? (E) entry.element : null;
    }

    private void insert0(long weight, E element) {
        int hole = ++this.size;
        int pred = hole >> 1;
        long predWeight = this.heap[pred].weight;

        while (predWeight > weight) {
            this.heap[hole] = this.heap[pred];
            hole = pred;
            pred >>= 1;
            predWeight = this.heap[pred].weight;
        }

        this.heap[hole] = newEntry(element, weight);
    }

    public void insertSeries(long weight, E[] elements) {
        ObjectUtil.checkNotNull(elements, "elements");
        if (elements.length == 0) return;

        this.ensureCapacity(this.size + elements.length);

        // Try and optimize insertion.
        boolean optimized = this.size == 0;
        if (!optimized) {
            optimized = true;
            for (int parentIdx = 0, currentIdx = this.size; parentIdx < currentIdx; parentIdx++) {
                if (weight < this.heap[parentIdx].weight) {
                    optimized = false;
                    break;
                }
            }
        }

        if (optimized) {
            // Parents are all less than series weight so we can directly insert.
            for (E element : elements) {
                ObjectUtil.checkNotNull(element, "element");

                this.heap[++this.size] = newEntry(element, weight);
            }
        } else {
            for (E element : elements) {
                ObjectUtil.checkNotNull(element, "element");
                this.insert0(weight, element);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public E poll() {
        if (this.size > 0) {
            E e = (E) this.heap[1].element;
            this.remove();
            return e;
        }
        return null;
    }

    public int size() {
        return this.size;
    }

    public void remove() {
        if (this.size == 0) {
            throw new NoSuchElementException("Heap is empty");
        }
        this.heap[1].release();
        int hole = 1;
        int succ = 2;
        int sz = this.size;

        while (succ < sz) {
            Entry entry1 = this.heap[succ];
            Entry entry2 = this.heap[succ + 1];

            if (entry1.weight > entry2.weight) {
                this.heap[hole] = entry2;
                succ++;
            } else {
                this.heap[hole] = entry1;
            }
            hole = succ;
            succ <<= 1;
        }

        // bubble up rightmost element
        Entry bubble = this.heap[sz];
        int pred = hole >> 1;
        while (this.heap[pred].weight > bubble.weight) { // must terminate since min at root
            this.heap[hole] = this.heap[pred];
            hole = pred;
            pred >>= 1;
        }

        // finally move data to hole
        this.heap[hole] = bubble;

        this.heap[sz] = INFIMUM; // mark as deleted

        this.size--;

        if ((this.size << 2) < this.heap.length && this.size > 4) {
            this.resize(this.size << 1);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isEmpty() {
        return this.size == 0;
    }

    @Override
    protected void deallocate() {
        while (this.size > 0) {
            Entry entry = this.heap[1];
            this.remove();
            entry.release();
        }
    }

    @Override
    public FastBinaryMinHeap<E> touch(Object hint) {
        return this;
    }

    private static class Entry extends AbstractReferenceCounted {
        private final ObjectPool.Handle<Entry> handle;
        private Object element;
        private long weight;

        private Entry(long weight) {
            this.weight = weight;
            this.handle = null;
        }

        private Entry(ObjectPool.Handle<Entry> handle) {
            this.handle = handle;
        }

        @Override
        protected void deallocate() {
            if (handle == null) return;
            this.element = null;
            this.weight = 0;
            this.handle.recycle(this);
        }

        @Override
        public Entry touch(Object hint) {
            return this;
        }
    }
}
