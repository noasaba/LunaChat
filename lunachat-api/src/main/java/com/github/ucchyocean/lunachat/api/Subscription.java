package com.github.ucchyocean.lunachat.api;

/** Idempotently removable observer registration. */
public interface Subscription extends AutoCloseable { @Override void close(); }
