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
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

import java.lang.reflect.Constructor;
import java.util.function.Consumer;

public class RakChannelFactory<T extends Channel> implements ChannelFactory<T> {

    private final Constructor<? extends T> constructor;
    private final Constructor<? extends DatagramChannel> datagramConstructor;
    private final Consumer<DatagramChannel> parentConsumer;

    private RakChannelFactory(Class<? extends T> clazz, Class<? extends DatagramChannel> datagramClass, Consumer<DatagramChannel> parentConsumer) {
        ObjectUtil.checkNotNull(clazz, "clazz");
        ObjectUtil.checkNotNull(datagramClass, "datagramClass");
        try {
            this.constructor = clazz.getConstructor(DatagramChannel.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Proxy class " + StringUtil.simpleClassName(clazz) +
                    " does not have a public non-arg constructor", e);
        }
        try {
            this.datagramConstructor = datagramClass.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Class " + StringUtil.simpleClassName(clazz) +
                    " does not have a public non-arg constructor", e);
        }
        this.parentConsumer = parentConsumer;
    }

    public static RakChannelFactory<RakServerChannel> server(Class<? extends DatagramChannel> clazz) {
        return new RakChannelFactory<>(RakServerChannel.class, clazz, null);
    }

    public static RakChannelFactory<RakServerChannel> server(Class<? extends DatagramChannel> clazz, Consumer<DatagramChannel> parentConsumer) {
        return new RakChannelFactory<>(RakServerChannel.class, clazz, parentConsumer);
    }

    public static RakChannelFactory<RakClientChannel> client(Class<? extends DatagramChannel> clazz) {
        return new RakChannelFactory<>(RakClientChannel.class, clazz, null);
    }

    public static RakChannelFactory<RakClientChannel> client(Class<? extends DatagramChannel> clazz, Consumer<DatagramChannel> parentConsumer) {
        return new RakChannelFactory<>(RakClientChannel.class, clazz, parentConsumer);
    }

    @Override
    public T newChannel() {
        try {
            DatagramChannel channel = datagramConstructor.newInstance();
            if (this.parentConsumer != null) {
                this.parentConsumer.accept(channel);
            }
            return constructor.newInstance(channel);
        } catch (Throwable t) {
            throw new ChannelException("Unable to create Channel from class " + constructor.getDeclaringClass(), t);
        }
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(RakChannelFactory.class) +
                '(' + StringUtil.simpleClassName(constructor.getDeclaringClass()) + ".class, " +
                StringUtil.simpleClassName(datagramConstructor.getDeclaringClass()) + ".class)";
    }
}
