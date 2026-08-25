package com.github.ucchyocean.lunachat.api;

/** Lifecycle/transport availability without exposing internal state machines. */
public enum NetworkState { READY, DEGRADED, UNAVAILABLE, RELOADING, SHUTTING_DOWN }
