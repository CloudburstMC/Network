/*
 * Copyright 2023 CloudburstMC
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

package org.cloudburstmc.netty.handler.codec.raknet.common;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.netty.channel.PendingMessages;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;

import java.util.concurrent.TimeUnit;

public class RakUnhandledMessagesQueue extends SimpleChannelInboundHandler<EncapsulatedPacket> {
    public static final String NAME = "rak-unhandled-messages-queue";
    // Only bridges the gap until NewIncomingConnection activates the channel.
    static final int MAX_QUEUED_MESSAGES = 128;
    static final int MAX_QUEUED_BYTES = 256 * 1024;

    private final RakChannel channel;
    private final PendingMessages<EncapsulatedPacket> messages = new PendingMessages<>(MAX_QUEUED_MESSAGES,
            MAX_QUEUED_BYTES, message -> message.getBuffer().readableBytes());
    private long addedTime;
    private boolean closing;
    private ScheduledFuture<?> future;

    public RakUnhandledMessagesQueue(RakChannel channel) {
        this.channel = channel;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.addedTime = System.currentTimeMillis();
        this.future = ctx.channel().eventLoop().scheduleAtFixedRate(() -> this.trySendMessages(ctx),
                0, 50, TimeUnit.MILLISECONDS);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        this.cancelFuture();
        this.messages.clear();
    }

    private void trySendMessages(ChannelHandlerContext ctx) {
        if (!this.channel.isActive()) {
            // Read here, child options are applied after this handler is added.
            long timeout = this.channel.config().getOption(RakChannelOption.RAK_SESSION_TIMEOUT);
            if (System.currentTimeMillis() - this.addedTime >= timeout) {
                this.close(RakDisconnectReason.TIMED_OUT);
            }
            return;
        }

        EncapsulatedPacket message;
        while ((message = this.messages.poll()) != null) {
            ctx.fireChannelRead(message);
        }

        ctx.pipeline().remove(this);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, EncapsulatedPacket msg) throws Exception {
        if (!this.channel.isActive()) {
            if (!this.closing && !this.messages.offer(msg.retain())) {
                this.close(RakDisconnectReason.QUEUE_TOO_LONG);
            }
            return;
        }

        this.trySendMessages(ctx);
        ctx.fireChannelRead(msg.retain());
    }

    private void close(RakDisconnectReason reason) {
        this.closing = true;
        this.cancelFuture();
        this.messages.clear();
        this.channel.pipeline().fireUserEventTriggered(reason).close();
    }

    private void cancelFuture() {
        if (this.future != null) {
            this.future.cancel(false);
            this.future = null;
        }
    }
}
