package com.github.ucchyocean.lunachat.core;

import com.github.ucchyocean.lunachat.api.NetworkState;
import com.github.ucchyocean.lunachat.api.NetworkStatus;
import com.github.ucchyocean.lunachat.api.NetworkStatusService;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

public final class LifecycleNetworkStatus implements NetworkStatusService {
    private record Value(NetworkState state, String code) {}
    private final Clock clock;
    private final AtomicReference<Value> current;
    public LifecycleNetworkStatus(Clock clock, NetworkState initial, String code) {
        this.clock = clock;
        this.current = new AtomicReference<>(new Value(initial, code));
    }
    public void update(NetworkState state, String code) { current.set(new Value(state, code)); }
    @Override public NetworkStatus current() {
        Value value = current.get();
        return new NetworkStatus(value.state(), value.code(), clock.instant());
    }
}
