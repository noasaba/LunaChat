package com.github.ucchyocean.lunachat.core;

import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;
import java.util.Collection;
import java.util.Optional;

public interface ChannelDirectory {
    Optional<ChannelDescriptor> find(ChannelId id);
    Optional<ChannelDescriptor> findByNameOrAlias(String value);
    Collection<ChannelDescriptor> snapshot();
}
