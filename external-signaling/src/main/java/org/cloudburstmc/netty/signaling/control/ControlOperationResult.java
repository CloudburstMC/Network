package org.cloudburstmc.netty.signaling.control;

import java.util.Optional;

/** Local delivery result. Receipt-only reconciliation never fabricates an application body. */
public final class ControlOperationResult {
    private final ControlLifecycleCodec.Receipt receipt;
    private final String body;
    private final Runnable current;
    private ControlOperationResult(ControlLifecycleCodec.Receipt receipt, String body, Runnable current) {
        this.receipt = receipt; this.body = body; this.current = current;
    }
    static ControlOperationResult delivered(ControlResultCodec.Result result, Runnable current) {
        return result.receipt().disposition().equals("committed")
                ? new ControlOperationResult(result.receipt(), result.body(), current) : reconciled(result.receipt());
    }
    static ControlOperationResult reconciled(ControlLifecycleCodec.Receipt receipt) { return new ControlOperationResult(receipt, null, null); }
    public ControlLifecycleCodec.Receipt receipt() { return receipt; }
    public boolean hasBody() { return body != null; }
    /** Returns owned bytes only while the original delivery's writer/key/deadline remains current. */
    public Optional<byte[]> bodyBytes() {
        if (body == null) return Optional.empty();
        requireCurrent(); return Optional.of(ControlJson.base64(body, ControlResultCodec.MAX_BODY_BYTES, false));
    }
    /** Application executors call this immediately before and after asynchronous state/key application. */
    public void requireCurrent() {
        if (current == null) throw new IllegalStateException("Receipt reconciliation has no live application body");
        current.run();
    }
    @Override public String toString() { return "ControlOperationResult[receipt=" + receipt + ", body=" + (body == null ? "absent" : "redacted") + "]"; }
}
