package com.nukkitx.network.synapse;

import java.net.ConnectException;

public class SynapseConnectException extends ConnectException {
    private final State state;

    public SynapseConnectException(State state) {
        super(state.name());
        this.state = state;
    }

    public enum State {
        LOGIN_FAILED,
        OUTDATED_CLIENT,
        OUTDATED_SERVER
    }
}
