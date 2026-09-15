/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.signaling;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Enforces the cap before allocating a response body; completion includes all bytes.
 */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int limit;
    private Flow.Subscription upstream;
    private int count;
    private boolean failed;

    LimitedBodySubscriber(int limit) {
        this.limit = limit;
    }

    public CompletionStage<byte[]> getBody() {
        return delegate.getBody();
    }

    public void onSubscribe(Flow.Subscription subscription) {
        upstream = subscription;
        delegate.onSubscribe(subscription);
    }

    public void onNext(List<ByteBuffer> buffers) {
        if (failed) {
            return;
        }

        for (ByteBuffer buf : buffers) {
            if (buf.remaining() > limit - count) {
                failed = true;
                upstream.cancel();
                delegate.onError(new IOException("Provider response exceeds limit"));
                return;
            }
            count += buf.remaining();
        }

        delegate.onNext(buffers);
    }

    public void onError(Throwable error) {
        if (!failed) {
            failed = true;
            delegate.onError(error);
        }
    }

    public void onComplete() {
        if (!failed) {
            delegate.onComplete();
        }
    }
}
