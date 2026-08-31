package com.github.ucchyocean.lunachat.core;

import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class InMemoryChannelDirectory implements ChannelDirectory {
    private final AtomicReference<Map<ChannelId, ChannelDescriptor>> channels =
            new AtomicReference<>(Map.of());

    public void replace(Collection<ChannelDescriptor> descriptors) {
        HashMap<ChannelId, ChannelDescriptor> replacement = new HashMap<>();
        descriptors.forEach(descriptor -> replacement.put(descriptor.id(), descriptor));
        channels.set(Map.copyOf(replacement));
    }

    public void put(ChannelDescriptor descriptor) {
        channels.updateAndGet(current -> {
            HashMap<ChannelId, ChannelDescriptor> replacement = new HashMap<>(current);
            replacement.put(descriptor.id(), descriptor);
            return Map.copyOf(replacement);
        });
    }
    public void remove(ChannelId id) {
        channels.updateAndGet(current -> {
            if (!current.containsKey(id)) return current;
            HashMap<ChannelId, ChannelDescriptor> replacement = new HashMap<>(current);
            replacement.remove(id);
            return Map.copyOf(replacement);
        });
    }
    @Override public Optional<ChannelDescriptor> find(ChannelId id) {
        return Optional.ofNullable(channels.get().get(id));
    }

    @Override public Optional<ChannelDescriptor> findByNameOrAlias(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String needle = value.toLowerCase(Locale.ROOT);
        return snapshot().stream().filter(channel -> channel.name().toLowerCase(Locale.ROOT).equals(needle)
                || channel.aliases().stream().anyMatch(alias -> alias.toLowerCase(Locale.ROOT).equals(needle))).findFirst();
    }

    @Override public List<ChannelDescriptor> snapshot() {
        return channels.get().values().stream()
                .sorted(Comparator.comparing(channel -> channel.id().value())).toList();
    }
}
