package com.github.ucchyocean.lunachat.api;

/** Non-blocking observer invoked after final LunaChat acceptance. */
@FunctionalInterface
public interface AcceptedMessageListener { void onAccepted(AcceptedMessage message); }
