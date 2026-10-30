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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.internal.logging.InternalLogger;

import java.util.function.BiFunction;

/**
 * Logs each request with its response code and time, at debug level.
 */
public class HttpLoggingHandler extends ChannelDuplexHandler {
    private final InternalLogger log;
    private final BiFunction<ChannelHandlerContext, HttpRequest, Object> requester;

    /** The latest request, which answers to earlier pipelined ones are logged against too. */
    private long startNanos;
    private String request;

    /**
     * @param requester Names who sent a request, such as the client a trusted proxy sent it for. Only asked
     *                  while debug logging is on
     */
    public HttpLoggingHandler(InternalLogger log, BiFunction<ChannelHandlerContext, HttpRequest, Object> requester) {
        this.log = log;
        this.requester = requester;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest req && log.isDebugEnabled()) {
            startNanos = System.nanoTime();
            request = requester.apply(ctx, req) + " " + req.method() + " " + req.uri();
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof HttpResponse res && request != null) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log.debug("{} -> {} ({} ms)", request, res.status().code(), ms);
        }
        ctx.write(msg, promise);
    }
}
