package com.github.ucchyocean.lunachat.api;

import java.util.Objects;
import java.util.UUID;

/** Admission result; accepted does not mean every client rendered the message. */
public record ExternalPublishResult(PublishStatus status, UUID messageId, boolean retryable, String diagnosticCode) {
    public ExternalPublishResult {
        Objects.requireNonNull(status, "status");
        diagnosticCode = ApiConstraints.text(diagnosticCode, "diagnosticCode", 64);
        if ((status == PublishStatus.ACCEPTED || status == PublishStatus.DUPLICATE) && messageId == null) {
            throw new IllegalArgumentException("accepted and duplicate results require messageId");
        }
    }
    public static ExternalPublishResult rejected(PublishStatus status, boolean retryable, String code) {
        return new ExternalPublishResult(status, null, retryable, code);
    }
}
