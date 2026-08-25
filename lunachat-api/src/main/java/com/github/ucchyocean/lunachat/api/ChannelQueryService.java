package com.github.ucchyocean.lunachat.api;

import java.util.Optional;

/** Read-only integration catalog keyed primarily by stable {@link ChannelId}. */
public interface ChannelQueryService {
    Optional<ChannelDescriptor> find(ChannelId id);
    Optional<ChannelDescriptor> findByNameOrAlias(String value);
    ChannelPage listVisibleToIntegration(ChannelPageRequest request);
}
