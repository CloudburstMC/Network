/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** Bounded transport-neutral exchange. Caller must establish diagnostic authentication before start(). */
public final class DiagnosticExchange {
    public static final int FRAME_BYTES = 56, CHALLENGE = 2, REPLY = 3, COMPLETION = 4;
    @FunctionalInterface public interface Sender { void send(int channel, byte[] ownedFrame); }
    private final String attempt;
    private final boolean host;
    private final Sender sender;
    private final byte[][] local = new byte[2][32], remote = new byte[2][];
    private final boolean[] replied = new boolean[2];
    private final int[] challengesReceived = new int[2], repliesReceived = new int[2];
    private int sentFrames, sentBytes, receivedFrames, receivedBytes, retries;
    private long lastRetryNanos;
    private boolean started, completionSent, completionReceived, failed;
    private byte[] pendingCompletion;

    public DiagnosticExchange(String attemptIdHex, boolean host, Sender sender) {
        unhex(attemptIdHex, 16); this.attempt = attemptIdHex; this.host = host; this.sender = java.util.Objects.requireNonNull(sender);
        SecureRandom random = new SecureRandom(); random.nextBytes(local[0]); random.nextBytes(local[1]);
        // AUTH has already been sent/verified before this exchange. It belongs to the same budget.
        if (host) { receivedFrames = 1; receivedBytes = 217; } else { sentFrames = 1; sentBytes = 217; }
    }
    public synchronized void start(long nowNanos) {
        if (started || failed) throw invalid(); started = true; lastRetryNanos = nowNanos;
        send(CHALLENGE, 0, local[0]); send(CHALLENGE, 1, local[1]);
    }
    public synchronized void receive(int channel, byte[] input) {
        if (!started || failed || input.length != FRAME_BYTES || channel < 0 || channel > 1) fail();
        if (++receivedFrames > MAX_FRAMES || (receivedBytes += input.length) > MAX_APPLICATION_SEND_BYTES) fail();
        byte[] frame = input.clone();
        if (!Arrays.equals(Arrays.copyOf(frame, 6), new byte[]{0,78,88,68,80,1}) || frame[7] != channel ||
                !Arrays.equals(Arrays.copyOfRange(frame, 8, 24), unhex(attempt, 16))) fail();
        int kind = frame[6]; byte[] nonce = Arrays.copyOfRange(frame, 24, 56);
        if (kind == CHALLENGE) {
            if (++challengesReceived[channel] > (channel == 0 ? 1 : 1 + MAX_UNRELIABLE_RETRIES)) fail();
            if (remote[channel] == null) remote[channel] = nonce;
            else if (!MessageDigest.isEqual(remote[channel], nonce)) fail();
            send(REPLY, channel, nonce);
        } else if (kind == REPLY) {
            if (++repliesReceived[channel] > (channel == 0 ? 1 : 1 + MAX_UNRELIABLE_RETRIES) || !MessageDigest.isEqual(local[channel], nonce)) fail();
            replied[channel] = true;
        } else if (kind == COMPLETION) {
            // Reliable completion can overtake the independent unreliable channel.
            if (channel != 0 || pendingCompletion != null) fail();
            pendingCompletion = nonce;
        } else fail();
        if (roundTripsDone() && !completionSent) {
            completionSent = true; send(COMPLETION, 0, completionHash());
        }
        if (roundTripsDone() && pendingCompletion != null) {
            if (!MessageDigest.isEqual(completionHash(), pendingCompletion)) fail();
            completionReceived = true;
        }
    }
    /** At most two application retries, preserving the original nonce. Caller owns the fixed attempt deadline. */
    public synchronized void tick(long nowNanos) {
        if (started && !failed && !replied[1] && retries < MAX_UNRELIABLE_RETRIES && nowNanos - lastRetryNanos >= 250_000_000L) {
            retries++; lastRetryNanos = nowNanos; send(CHALLENGE, 1, local[1]);
        }
    }
    public synchronized boolean complete() { return !failed && completionSent && completionReceived && roundTripsDone(); }
    public synchronized int sentFrames() { return sentFrames; }
    public synchronized int sentBytes() { return sentBytes; }
    public synchronized int receivedFrames() { return receivedFrames; }
    public synchronized int receivedBytes() { return receivedBytes; }
    private boolean roundTripsDone() { return replied[0] && replied[1] && remote[0] != null && remote[1] != null; }
    private byte[] completionHash() {
        byte[][] prober = host ? remote : local, server = host ? local : remote;
        return digest(concat(domain("completion"), unhex(attempt,16), prober[0], prober[1], server[0], server[1]));
    }
    private void send(int kind, int channel, byte[] value) {
        if (failed || ++sentFrames > MAX_FRAMES || (sentBytes += FRAME_BYTES) > MAX_APPLICATION_SEND_BYTES) fail();
        try { sender.send(channel, encode(attempt, kind, channel, value)); }
        catch (RuntimeException failure) { failed = true; throw failure; }
    }
    public static byte[] encode(String attemptIdHex, int kind, int channel, byte[] value) {
        if (value.length != 32 || kind < CHALLENGE || kind > COMPLETION || channel < 0 || channel > 1 || kind == COMPLETION && channel != 0) throw invalid();
        return ByteBuffer.allocate(FRAME_BYTES).put(new byte[]{0,78,88,68,80,1,(byte)kind,(byte)channel})
                .put(unhex(attemptIdHex,16)).put(value).array();
    }
    private void fail() { failed = true; throw invalid(); }
}
