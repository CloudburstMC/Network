package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFactory;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

import java.lang.reflect.Constructor;

public class RakChannelFactory<T extends Channel> implements ChannelFactory<T> {

    private final Constructor<? extends T> constructor;
    private final Constructor<? extends DatagramChannel> datagramConstructor;

    private RakChannelFactory(Class<? extends T> clazz, Class<? extends DatagramChannel> datagramClass) {
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
    }

    public static RakChannelFactory<RakServerChannel> server(Class<? extends DatagramChannel> clazz) {
        return new RakChannelFactory<>(RakServerChannel.class, clazz);
    }

    public static RakChannelFactory<RakClientChannel> client(Class<? extends DatagramChannel> clazz) {
        return new RakChannelFactory<>(RakClientChannel.class, clazz);
    }

    @Override
    public T newChannel() {
        try {
            return constructor.newInstance(datagramConstructor.newInstance());
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
