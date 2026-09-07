package com.github.ucchyocean.lunachat.core.network;

/** Single source of truth for the production Paper/Velocity wire identity. */
public final class NetworkProtocol {
    public static final int VERSION = 8;
    public static final String CHANNEL = "lunachat:network_v" + VERSION;

    private NetworkProtocol() {}
}
