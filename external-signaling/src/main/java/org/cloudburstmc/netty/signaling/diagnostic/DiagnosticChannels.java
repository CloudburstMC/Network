/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import tel.schich.libdatachannel.*;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.invalid;

/** The diagnostic transport profile: two channels, one reliable 56-byte inbound frame. */
final class DiagnosticChannels implements DataChannelCallback.Message {
    private final DataChannel[] channels = new DataChannel[2];
    private final ArrayBlockingQueue<DataChannel> pending = new ArrayBlockingQueue<>(2);
    private volatile boolean connected, failed, protocolFailed;
    private byte[] incoming;
    private int receivedFrames, receivedBytes;

    void attach(PeerConnection peer, boolean host) {
        peer.onStateChange.register((p, state) -> {
            if (state == PeerState.RTC_CONNECTED) connected = true;
            if (state == PeerState.RTC_FAILED || state == PeerState.RTC_CLOSED) failed = true;
        });
        peer.onDataChannel.register((p, channel) -> {
            if (!host || !pending.offer(channel)) protocolFailed = true;
        });
        if (!host) for (int index = 0; index < 2; index++) {
            install(peer.createDataChannel(label(index), DataChannelInitSettings.DEFAULT.withReliability(
                    DataChannelReliability.DEFAULT.withUnordered(index == 1).withUnreliable(index == 1).withMaxRetransmits(0))));
        }
    }

    boolean ready() {
        DataChannel channel;
        while ((channel = pending.poll()) != null) install(channel);
        return connected && channels[0] != null && channels[1] != null
                && channels[0].isOpen() && channels[1].isOpen();
    }

    private void install(DataChannel channel) {
        int index = channel.label().equals(label(0)) ? 0 : channel.label().equals(label(1)) ? 1 : -1;
        DataChannelReliability reliability = channel.reliability();
        if (index < 0 || channels[index] != null || !channel.protocol().isEmpty()
                || reliability.isUnordered() != (index == 1) || reliability.isUnreliable() != (index == 1)
                || reliability.maxRetransmits() != 0 || !reliability.maxPacketLifeTime().isZero()) {
            protocolFailed = true;
            throw invalid();
        }
        channels[index] = channel;
        channel.onClosed.register(ignored -> failed = true);
        channel.onError.register((ignored, error) -> failed = true);
        channel.onMessage.register(this);
    }

    boolean failed() { return failed; }
    boolean protocolFailed() { return protocolFailed; }
    synchronized int receivedFrames() { return receivedFrames; }
    synchronized int receivedBytes() { return receivedBytes; }

    @Override public void onText(DataChannel channel, String text) { protocolFailed = true; }
    @Override public void onBinary(DataChannel channel, ByteBuffer bytes) {
        receive(channel == channels[0], bytes);
    }

    // Native callback memory is borrowed. Copy the sole permitted frame before returning.
    synchronized void receive(boolean reliable, ByteBuffer bytes) {
        if (protocolFailed) return;
        if (!reliable || bytes.remaining() != DiagnosticExchange.FRAME_BYTES || receivedFrames != 0) {
            protocolFailed = true;
            return;
        }
        receivedFrames = 1;
        receivedBytes = DiagnosticExchange.FRAME_BYTES;
        incoming = new byte[DiagnosticExchange.FRAME_BYTES];
        bytes.get(incoming);
    }

    synchronized byte[] poll() {
        byte[] frame = incoming;
        incoming = null;
        return frame;
    }

    synchronized void clear() {
        incoming = null;
        pending.clear();
        java.util.Arrays.fill(channels, null);
    }

    void send(int channel, byte[] bytes) {
        if (protocolFailed || channel != 0 || bytes.length != DiagnosticExchange.FRAME_BYTES) throw invalid();
        channels[0].sendMessage(ByteBuffer.allocateDirect(bytes.length).put(bytes).flip());
    }

    private static String label(int index) { return index == 0 ? "ReliableDataChannel" : "UnreliableDataChannel"; }
}
