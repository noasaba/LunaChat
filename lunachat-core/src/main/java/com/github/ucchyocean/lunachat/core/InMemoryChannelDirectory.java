package com.github.ucchyocean.lunachat.core;

import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryChannelDirectory implements ChannelDirectory {
    private final ConcurrentHashMap<ChannelId, ChannelDescriptor> channels = new ConcurrentHashMap<>();

    public void replace(Collection<ChannelDescriptor> descriptors) {
        channels.clear();
        descriptors.forEach(descriptor -> channels.put(descriptor.id(), descriptor));
    }

    public void put(ChannelDescriptor descriptor) { channels.put(descriptor.id(), descriptor); }
    public void remove(ChannelId id) { channels.remove(id); }
    @Override public Optional<ChannelDescriptor> find(ChannelId id) { return Optional.ofNullable(channels.get(id)); }

    @Override public Optional<ChannelDescriptor> findByNameOrAlias(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String needle = value.toLowerCase(Locale.ROOT);
        return snapshot().stream().filter(channel -> channel.name().toLowerCase(Locale.ROOT).equals(needle)
                || channel.aliases().stream().anyMatch(alias -> alias.toLowerCase(Locale.ROOT).equals(needle))).findFirst();
    }

    @Override public List<ChannelDescriptor> snapshot() {
        return channels.values().stream().sorted(Comparator.comparing(channel -> channel.id().value())).toList();
    }
}
