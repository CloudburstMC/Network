package com.nukkitx.network.raknet.util;

import com.nukkitx.network.raknet.RakNetUtils;
import io.netty.util.ReferenceCountUtil;

import java.util.function.Consumer;

public class RoundRobinArray<E> {

    private final Object[] elements;
    private final int mask;

    public RoundRobinArray(int fixedCapacity) {
        fixedCapacity = RakNetUtils.powerOfTwoCeiling(fixedCapacity);

        this.elements = new Object[fixedCapacity];
        this.mask = fixedCapacity - 1;
    }

    @SuppressWarnings("unchecked")
    public E get(int index) {
        return (E) this.elements[index & this.mask];
    }

    public void set(int index, E value) {
        int idx = index & this.mask;
        Object element = this.elements[idx];
        this.elements[idx] = value;
        // Make sure to release any reference counted objects that get overwritten.
        ReferenceCountUtil.release(element);
    }

    @SuppressWarnings("unchecked")
    public E remove(int index) {
        int idx = index & this.mask;
        E removed = (E) this.elements[idx];
        this.elements[idx] = null;
        return removed;
    }

    public int size() {
        return this.elements.length;
    }

    @SuppressWarnings("unchecked")
    public void forEach(Consumer<E> consumer) {
        for (Object element : elements) {
            consumer.accept((E) element);
        }
    }
}
