/*
 * Copyright 2022 CloudburstMC
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

package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.StringUtil;
import org.cloudburstmc.netty.handler.codec.raknet.cookieservice.RakServerCookieHandler;

import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public class RakChannelFactory<T extends Channel> implements ChannelFactory<T> {

    private final Class<T> channelClass;
    private final Function<DatagramChannel, T> constructor;
    private final Constructor<? extends DatagramChannel> datagramConstructor;
    private final Consumer<DatagramChannel> parentConsumer;

    private RakChannelFactory(Class<T> channelClass, Function<DatagramChannel, T> constructor, Class<? extends DatagramChannel> datagramClass,
                              Consumer<DatagramChannel> parentConsumer) {
        Objects.requireNonNull(channelClass, "channelClass");
        Objects.requireNonNull(datagramClass, "datagramClass");
        Objects.requireNonNull(constructor, "constructor");
        this.channelClass = channelClass;
        this.constructor = constructor;

        try {
            this.datagramConstructor = datagramClass.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Class " + StringUtil.simpleClassName(datagramClass) +
                    " does not have a public non-arg constructor", e);
        }
        this.parentConsumer = parentConsumer;
    }

    public static RakChannelFactory<RakServerChannel> server(Class<? extends DatagramChannel> clazz) {
        return new RakChannelFactory<>(RakServerChannel.class, RakServerChannel::new, clazz, null);
    }

    public static RakChannelFactory<RakServerChannel> server(Class<? extends DatagramChannel> clazz, Consumer<DatagramChannel> parentConsumer) {
        return new RakChannelFactory<>(RakServerChannel.class, RakServerChannel::new, clazz, parentConsumer);
    }

    public static RakChannelFactory<RakServerChannel> server(Class<? extends DatagramChannel> clazz, Consumer<DatagramChannel> parentConsumer, Consumer<RakChannel> childConsumer) {
        return new RakChannelFactory<>(RakServerChannel.class, ch -> new RakServerChannel(ch, childConsumer), clazz, parentConsumer);
    }

    public static RakChannelFactory<RakClientChannel> client(Class<? extends DatagramChannel> clazz) {
        return new RakChannelFactory<>(RakClientChannel.class, RakClientChannel::new, clazz, null);
    }

    public static RakChannelFactory<RakClientChannel> client(Class<? extends DatagramChannel> clazz, Consumer<DatagramChannel> parentConsumer) {
        return new RakChannelFactory<>(RakClientChannel.class, RakClientChannel::new, clazz, parentConsumer);
    }

    /**
     * Creates a factory for a specialized RakServerChannel designed exclusively for issuing cookies.
     * This service strips away session management, routing, tail handling, rate limiting, and ping handling
     * to run efficiently as a stateless cookie issuer.
     *
     * @param clazz The underlying DatagramChannel class.
     * @return A factory for cookie-service enabled RakServerChannels.
     */
    public static RakChannelFactory<RakServerChannel> cookieService(Class<? extends DatagramChannel> clazz) {
        return new RakChannelFactory<>(RakServerChannel.class, channel -> {
            RakServerChannel rakChannel = new RakServerChannel(channel, null) {
                @Override
                public RakChildChannel createChildChannel(InetSocketAddress address, InetSocketAddress localAddress, long clientGuid, int mtu) {
                    return null;
                }

                @Override
                protected void initPipeline() {
                    this.pipeline().addLast(RakServerCookieHandler.NAME, new RakServerCookieHandler());
                    this.pipeline().addLast("rak-cookie-sink", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ReferenceCountUtil.release(msg);
                        }
                    });
                }
            };            
            return rakChannel;
        }, clazz, null);
    }

    @Override
    public T newChannel() {
        try {
            DatagramChannel channel = datagramConstructor.newInstance();
            if (this.parentConsumer != null) {
                this.parentConsumer.accept(channel);
            }
            return constructor.apply(channel);
        } catch (Throwable t) {
            throw new ChannelException("Unable to create Channel from class " + this.channelClass, t);
        }
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(RakChannelFactory.class) +
                '(' + StringUtil.simpleClassName(this.channelClass) + ".class, " +
                StringUtil.simpleClassName(datagramConstructor.getDeclaringClass()) + ".class)";
    }
}
