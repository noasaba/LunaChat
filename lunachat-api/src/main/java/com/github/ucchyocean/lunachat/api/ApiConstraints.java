package com.github.ucchyocean.lunachat.api;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

final class ApiConstraints {
    static final int MAX_CONTENT_CHARS = 8192;
    static final int MAX_CONTENT_BYTES = 32767;
    static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,63}");

    private ApiConstraints() {}

    static String text(String value, String field, int maxChars) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maxChars || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is blank, too long, or contains NUL");
        }
        return value;
    }

    static String content(String value) {
        text(value, "content", MAX_CONTENT_CHARS);
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("content exceeds UTF-8 byte limit");
        }
        return value;
    }

    static String namespace(String value) {
        Objects.requireNonNull(value, "namespace");
        if (!NAMESPACE.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid namespace");
        }
        return value;
    }
}
