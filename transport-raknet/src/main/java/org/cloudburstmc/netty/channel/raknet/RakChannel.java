package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

public interface RakChannel extends Channel {

    ChannelPipeline rakPipeline();
}
