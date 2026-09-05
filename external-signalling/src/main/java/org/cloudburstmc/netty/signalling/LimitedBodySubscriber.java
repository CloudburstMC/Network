package org.cloudburstmc.netty.signalling;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Enforces the cap before allocating a response body; completion includes all bytes. */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int limit;
    private Flow.Subscription upstream;
    private int count;
    private boolean failed;
    LimitedBodySubscriber(int limit) { this.limit = limit; }
    public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    public void onSubscribe(Flow.Subscription subscription) { upstream = subscription; delegate.onSubscribe(subscription); }
    public void onNext(List<ByteBuffer> buffers) {
        if (failed) return;
        for (ByteBuffer b : buffers) { if (b.remaining() > limit - count) { failed = true; upstream.cancel(); delegate.onError(new IOException("Provider response exceeds limit")); return; } count += b.remaining(); }
        delegate.onNext(buffers);
    }
    public void onError(Throwable error) { if (!failed) { failed = true; delegate.onError(error); } }
    public void onComplete() { if (!failed) delegate.onComplete(); }
}
