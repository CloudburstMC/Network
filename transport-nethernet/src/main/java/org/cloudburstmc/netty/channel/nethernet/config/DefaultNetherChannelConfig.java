package org.cloudburstmc.netty.channel.nethernet.config;

import dev.kastle.webrtc.PortAllocatorConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultNetherChannelConfig extends DefaultChannelConfig {
    private final Map<ChannelOption<?>, Object> options = new ConcurrentHashMap<>();

    private volatile PortAllocatorConfig portAllocatorConfig = new PortAllocatorConfig()
        .setDisableTcp(true)
        .setEnableIpv6(true)
        .setEnableIpv6OnWifi(true)
        .setEnableAnyAddressPorts(true)
        .setDisableAdapterEnumeration(false)
        .setEnableSharedSocket(true)
        .setEnableAnyAddressPorts(true)
        .setDisableCostlyNetworks(true)
        .setDisableLinkLocalNetworks(true);

    public DefaultNetherChannelConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return this.getOptions(
                super.getOptions(), 
                NetherChannelOption.NETHER_PORT_ALLOCATOR_CONFIG
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {

        if (option == NetherChannelOption.NETHER_PORT_ALLOCATOR_CONFIG) {
            return (T) this.portAllocatorConfig;
        } else if (options.containsKey(option)) {
            return (T) options.get(option);
        }

        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        if (option == NetherChannelOption.NETHER_PORT_ALLOCATOR_CONFIG) {
            this.setPortAllocatorConfig((PortAllocatorConfig) value);
            return true;
        } else if (super.setOption(option, value)) {
            return true;
        } else {
            options.put(option, value);
            return true;
        }
    }

    void setPortAllocatorConfig(PortAllocatorConfig portAllocatorConfig) {
        this.portAllocatorConfig = portAllocatorConfig;
    }
}