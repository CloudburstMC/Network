package org.cloudburstmc.netty.channel.nethernet.backend;

import io.github.sendablemetatype.webrtc.RTCDataChannel;
import io.github.sendablemetatype.webrtc.RTCDataChannelBuffer;
import io.github.sendablemetatype.webrtc.RTCDataChannelSendObserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Consumer;

/** Adapts native send results without running transport operations on the callback thread. */
public final class WebRtcSend {
    private WebRtcSend() {
    }

    /**
     * Copies a buffer window into the native send operation. Completion receives
     * null for local acceptance or the native error. It runs on the native
     * callback thread and must not block or call WebRTC synchronously.
     */
    public static void send(RTCDataChannel channel, ByteBuffer data, Consumer<Throwable> completion) {
        Objects.requireNonNull(completion, "completion");
        channel.sendAsync(new RTCDataChannelBuffer(data, true), new RTCDataChannelSendObserver() {
            @Override
            public void onSuccess() {
                completion.accept(null);
            }

            @Override
            public void onFailure(String error) {
                completion.accept(new IOException("Native WebRTC send failed: " + error));
            }
        });
    }
}
