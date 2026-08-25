package com.github.ucchyocean.lunachat.core;

import com.github.ucchyocean.lunachat.api.AcceptedMessage;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ExternalDeliverySink {
    /** Commit to the bounded delivery pipeline; true does not mean every client rendered the message. */
    CompletionStage<AcceptedMessage> commit(AcceptedMessage proposedMessage);
}
