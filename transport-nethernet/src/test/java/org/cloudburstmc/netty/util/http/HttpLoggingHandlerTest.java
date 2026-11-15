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

package org.cloudburstmc.netty.util.http;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.internal.logging.InternalLogger;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpLoggingHandlerTest {

    /** The arguments after the format of each debug call, from a logger whose debug level is on when asked. */
    private static InternalLogger logger(boolean debug, List<Object[]> lines) {
        return (InternalLogger) Proxy.newProxyInstance(InternalLogger.class.getClassLoader(),
                new Class<?>[]{InternalLogger.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isDebugEnabled" -> debug;
                    case "debug" -> {
                        lines.add(args.length == 2 && args[1] instanceof Object[] varargs ? varargs
                                : Arrays.copyOfRange(args, 1, args.length));
                        yield null;
                    }
                    case "name" -> "test";
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
    }

    @Test
    void namesTheRequesterItIsGiven() {
        List<Object[]> lines = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpLoggingHandler(logger(true, lines),
                (ctx, req) -> "/192.0.2.7:4000 via /127.0.0.1:5000"));

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/v1/join"));
        channel.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));

        assertEquals(1, lines.size());
        Object[] line = lines.get(0);
        assertEquals("/192.0.2.7:4000 via /127.0.0.1:5000 GET /v1/join", line[0]);
        assertEquals(200, line[1]);
        channel.finishAndReleaseAll();
    }

    @Test
    void asksNothingWhileDebugIsOff() {
        // Naming a requester may parse headers, which a quiet log has no use for
        List<Object[]> lines = new ArrayList<>();
        AtomicInteger asked = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpLoggingHandler(logger(false, lines),
                (ctx, req) -> asked.incrementAndGet()));

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/v1/join"));
        channel.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));

        assertEquals(0, asked.get());
        assertTrue(lines.isEmpty());
        channel.finishAndReleaseAll();
    }
}
