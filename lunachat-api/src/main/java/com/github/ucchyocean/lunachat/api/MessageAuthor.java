package com.github.ucchyocean.lunachat.api;

import java.util.Objects;
import java.util.UUID;

/** Stable author identity without platform-specific player or Discord objects. */
public sealed interface MessageAuthor permits MessageAuthor.Player, MessageAuthor.External, MessageAuthor.System {
    record Player(UUID uuid, String accountName, String displayName) implements MessageAuthor {
        public Player {
            Objects.requireNonNull(uuid, "uuid");
            accountName = ApiConstraints.text(accountName, "accountName", 16);
            displayName = ApiConstraints.text(displayName, "displayName", 256);
        }
    }
    record External(String namespace, String stableId, String displayName) implements MessageAuthor {
        public External {
            namespace = ApiConstraints.namespace(namespace);
            stableId = ApiConstraints.text(stableId, "stableId", 128);
            displayName = ApiConstraints.text(displayName, "displayName", 256);
        }
    }
    record System(String name) implements MessageAuthor {
        public System { name = ApiConstraints.text(name, "name", 128); }
    }
}
