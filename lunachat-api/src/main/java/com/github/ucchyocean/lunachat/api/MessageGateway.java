package com.github.ucchyocean.lunachat.api;

import java.util.concurrent.CompletionStage;

/** Authority message observation and idempotent external publication. */
public interface MessageGateway {
    Subscription observeAcceptedMessages(AcceptedMessageListener listener);
    CompletionStage<ExternalPublishResult> publishExternal(ExternalMessageRequest request);
}
