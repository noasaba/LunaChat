# LunaChat 4 Integration API design

## Supported topology

Standalone uses `LunaBridge-Paper -> Bukkit ServicesManager -> LunaChat-Paper`.
Network mode uses `LunaBridge-Velocity -> LunaChatApiProvider -> LunaChat-Velocity`
and authenticated `lunachat:network_v3` frames between Velocity and every Paper.
A Paper configured as `network_edge` deliberately lacks observe/publish
capabilities, so a standalone bridge cannot start there.

| Module | Responsibility | Must not own |
| --- | --- | --- |
| `lunachat-api` | Immutable contracts, roles, capabilities, result codes | Bukkit, Velocity, JDA, crypto/wire types |
| `lunachat-core` | Bounded gateway, channel query, dedup, secure frames, retry state | Platform lifecycle and rendering |
| `lunachat-paper` | Existing chat semantics, final-content boundary, standalone authority, network edge | Discord/JDA and network authority |
| `lunachat-velocity` | Durable channel authority, authenticated routing, authority API/provider | Paper rendering and Discord tokens |
| `lunachat-api-testkit` | Reusable authority/edge contract tests | Product implementation |

The old `com.github.ucchyocean.lc` and `com.github.ucchyocean.lc3` APIs remain
present. The new API is additive under `com.github.ucchyocean.lunachat.api`.

## Acceptance contract

`AcceptedMessage` is emitted after LunaChat authorization, filtering,
Japanize/event transformation, and final string selection, immediately before
local rendering. It does not assert that all clients rendered the message.
`messageId` identifies one logical delivery and is stable across retries.
Observers run on a bounded, isolated executor; exceptions do not fail chat.
The authority keeps bounded receipts until `expiresAt + 5 minutes`. Receipt
capacity exhaustion rejects new admission rather than evicting live identities.

External publish commits only after a Paper has accepted the final content.
The provider identity is the idempotency key. A duplicate returns the first
logical UUID and never renders or notifies again. External origin metadata is
preserved across Paper and Velocity; a bridge must relay only `MINECRAFT`
origins to avoid Discord loops.

## Discovery and lifecycle

Paper registers `LunaChatIntegrationApi` with Bukkit `ServicesManager` after
channel initialization and unregisters it before shutdown. Velocity plugin ID
is `lunachat`; its plugin instance implements `LunaChatApiProvider`, and
`current()` is empty until `ProxyInitializeEvent` completes or after shutdown.
Consumers depend on `lunachat-api` as compile-only and must not shade another
copy of the API into the same runtime.

Minimal Paper consumer:

```java
RegisteredServiceProvider<LunaChatIntegrationApi> registration =
    Bukkit.getServicesManager().getRegistration(LunaChatIntegrationApi.class);
LunaChatIntegrationApi api = registration == null ? null : registration.getProvider();
if (api == null
        || !api.capabilities().contains(Capability.OBSERVE_ACCEPTED_MESSAGES)
        || !api.capabilities().contains(Capability.PUBLISH_EXTERNAL_MESSAGES)
        || api.runtimeRole() == RuntimeRole.NETWORK_EDGE) {
    throw new IllegalStateException("LunaChat authority is unavailable");
}
Subscription subscription = api.messages().observeAcceptedMessages(message -> {
    if (message.origin().kind() == OriginKind.MINECRAFT) relayToDiscord(message);
});
```

Minimal external publish:

```java
ExternalMessageRequest request = new ExternalMessageRequest(
    channelId,
    new ExternalMessageIdentity("lunabridge:discord", discordMessageId),
    new MessageAuthor.External("lunabridge:discord", discordUserId, displayName),
    content, Instant.now(), Duration.ofMinutes(5));
api.messages().publishExternal(request).thenAccept(result -> {
    if (result.retryable()) scheduleBoundedRetry(result.diagnosticCode());
});
```

Close every `Subscription` during consumer disable. Never wait synchronously on
the returned stage from a Paper main thread or Velocity event thread.

## Channel identity and migration

Channel YAML schema 1 adds `channel_id` (canonical UUID), `schema_version`, and
`accepts_external_messages`. Startup first copies v0 files to
`migration-backup-v0/channels`, then writes the new values. Re-running preserves
the ID and backup. A future schema aborts plugin startup without rewriting data.
Names and aliases are display/lookup values only; integrations persist the ID.

Velocity owns `plugins/lunachat/channels.properties`.  It is the only channel
definition source in a network topology: each `channel.<uuid>.name`,
`.aliases`, and `.external` entry is distributed to every authenticated Paper
edge. Paper never proposes, creates, migrates, or writes channel YAML in this
mode. A Paper receives the catalog before it is eligible for network message
delivery and acknowledges the exact snapshot; mismatches fail closed.

Legacy Paper YAML is not a source in network mode. A same-name or alias entry
with a different legacy ID is replaced in memory by Velocity's canonical ID on
each catalog sync; no Paper YAML is rewritten. Standalone Paper keeps the
normal YAML-owned behavior.

LunaBridge in a network topology must run on Velocity and keep its Discord
mapping there, keyed by the authority ChannelId. LunaChat has no Discord/JDA
dependency and Paper-side mappings are not consulted in network mode.

## Explicit initial limitations

Wire v1 routes accepted channel messages and external publishes. Existing
network-wide membership, invite, mute/ban mutation, and cross-backend `/tell`
and `/r` state have not yet been moved from the legacy Bungee implementation
into the Velocity authority. Those operations remain backend-local and must not
be advertised as globally committed while the authority is unavailable.
Offline private messages are unsupported; no offline queue is created.
