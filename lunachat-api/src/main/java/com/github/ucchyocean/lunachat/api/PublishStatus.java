package com.github.ucchyocean.lunachat.api;

/** Exhaustive publication outcome; no implicit local fallback exists. */
public enum PublishStatus { ACCEPTED, DUPLICATE, CHANNEL_NOT_FOUND, FORBIDDEN, INVALID, OVER_CAPACITY, UNAVAILABLE, EXPIRED }
