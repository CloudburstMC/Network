package org.cloudburstmc.netty.util;

public class IntRange {
    public int start;
    public int end;

    public IntRange(int num) {
        this(num, num);
    }

    public IntRange(int start, int end) {
        this.start = start;
        this.end = end;
    }
}