package com.nukkitx.network;

import io.netty.channel.epoll.Native;
import lombok.experimental.UtilityClass;

import java.util.Optional;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@UtilityClass
public final class NativeUtils {
    private static final Optional<int[]> KERNEL_VERSION;
    private static final int[] REUSEPORT_VERSION = new int[]{3, 9, 0};
    private static final boolean REUSEPORT_AVAILABLE;

    static {
        String kernelVersion;
        try {
            kernelVersion = Native.KERNEL_VERSION;
        } catch (Throwable e) {
            kernelVersion = null;
        }
        if (kernelVersion != null && kernelVersion.contains("-")) {
            int index = kernelVersion.indexOf('-');
            if (index > -1) {
                kernelVersion = kernelVersion.substring(0, index);
            }
            int[] kernalVer = fromString(kernelVersion);
            KERNEL_VERSION = Optional.of(kernalVer);
            REUSEPORT_AVAILABLE = checkVersion(kernalVer, 0);
        } else {
            KERNEL_VERSION = Optional.empty();
            REUSEPORT_AVAILABLE = false;
        }
    }

    public static Optional<int[]> getKernelVersion() {
        return KERNEL_VERSION;
    }

    public static boolean isReusePortAvailable() {
        return REUSEPORT_AVAILABLE;
    }

    private static int[] fromString(String ver) {
        String[] parts = ver.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("At least 2 version numbers required");
        }

        return new int[]{
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                parts.length == 2 ? 0 : Integer.parseInt(parts[2])
        };
    }

    private static boolean checkVersion(int[] ver, int i) {
        if (ver[i] > REUSEPORT_VERSION[i]) {
            return true;
        } else if (ver[i] == REUSEPORT_VERSION[i]) {
            if (ver.length == (i + 1)) {
                return true;
            } else {
                return checkVersion(ver, i + 1);
            }
        }
        return false;
    }
}
