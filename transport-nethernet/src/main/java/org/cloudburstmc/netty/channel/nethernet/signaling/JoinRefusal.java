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
package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Why a join did not happen, and the status it is refused with.
 * <p>
 * The constants are what this signaling raises on its own. A host answering something else
 * builds one. Only the status reaches the peer for now, since the client shows nothing else; a
 * message can be added here later without changing what callers build.
 */
public final class JoinRefusal {
    /** The offer carried no usable identity assertion. */
    public static final JoinRefusal INVALID_IDENTITY = new JoinRefusal(HttpResponseStatus.UNAUTHORIZED);
    /** The player filter turned the peer away. */
    public static final JoinRefusal REJECTED = new JoinRefusal(HttpResponseStatus.FORBIDDEN);
    /** There is no room for another player. */
    public static final JoinRefusal FULL = new JoinRefusal(HttpResponseStatus.SERVICE_UNAVAILABLE);
    /** Another join for this network ID is already waiting for an answer. */
    public static final JoinRefusal DUPLICATE = new JoinRefusal(HttpResponseStatus.CONFLICT);
    /** Nothing produced an answer in time. */
    public static final JoinRefusal TIMEOUT = new JoinRefusal(HttpResponseStatus.GATEWAY_TIMEOUT);
    /** Signaling is not in a state to answer, or something failed while answering. */
    public static final JoinRefusal ERROR = new JoinRefusal(HttpResponseStatus.INTERNAL_SERVER_ERROR);

    private final HttpResponseStatus status;

    public JoinRefusal(HttpResponseStatus status) {
        // A 2xx leaves the client parsing an answer we never wrote
        if (status.code() >= 200 && status.code() < 300) {
            throw new IllegalArgumentException("A refusal cannot tell a client the join worked: " + status);
        }
        this.status = status;
    }

    public HttpResponseStatus status() {
        return this.status;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JoinRefusal refusal && this.status.equals(refusal.status);
    }

    @Override
    public int hashCode() {
        return this.status.hashCode();
    }

    @Override
    public String toString() {
        return this.status.toString();
    }
}
