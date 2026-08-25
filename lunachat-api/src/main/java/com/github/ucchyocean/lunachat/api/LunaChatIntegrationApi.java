package com.github.ucchyocean.lunachat.api;

import java.util.Set;

/** Entry point exposed by an initialized LunaChat runtime. */
public interface LunaChatIntegrationApi {
    ApiVersion apiVersion();
    RuntimeRole runtimeRole();
    Set<Capability> capabilities();
    ChannelQueryService channels();
    MessageGateway messages();
    NetworkStatusService networkStatus();
}
