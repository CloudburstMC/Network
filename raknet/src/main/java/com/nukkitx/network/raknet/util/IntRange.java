package com.nukkitx.network.raknet.util;

import com.nukkitx.network.util.Preconditions;
import lombok.Value;

@Value
public class IntRange {
    private final int start;
    private final int end;

    public IntRange(int num) {
        this(num, num);
    }

    public IntRange(int start, int end) {
        Preconditions.checkArgument(start <= end, "start is less than end");
        this.start = start;
        this.end = end;
    }
}