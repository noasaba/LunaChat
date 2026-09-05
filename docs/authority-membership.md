# Authority membership (4.0.15-SNAPSHOT / wire 5)

Status: implementation and automated-test candidate. Live two-Paper/Discord
verification remains required before deployment.

## First Velocity bootstrap

An empty Velocity authority is intentional: it never invents a channel from a
Paper configuration. From the Velocity console, after `LunaChat authority
ready`, create the canonical channel and settings before bringing Paper edges
online:

```text
/lunachat create global true
/lunachat default global
/lunachat force global
/lunachat list
```

`create <name> [acceptsExternalMessages]`, `list`, `delete <name>`,
`alias <name> <alias|->`, `default <name|->`, and `force <name,...|->` are
Velocity-console authority commands. A referenced default/force channel cannot
be deleted. They write `channels.properties` atomically and retain stable UUIDs.
This supplies the canonical `global` that `lunabridge setup <discord-id> global`
resolves through the shared Integration API.

After bootstrap, `/lunachat create <name>` executed on any connected Paper is
an authority request rather than a local write. Velocity allocates and persists
the UUID, broadcasts the same definition to every Paper, and Paper reports
success only after that canonical STATE is applied. Authority downtime or an
invalid/duplicate request leaves every Paper unchanged. The optional Paper
description argument is not authority state and is therefore not persisted in
network mode.

Every `/lunachat` subcommand, including `list`, requires Velocity permission
`lunachat.admin`; the Velocity console is always allowed. Under systemd, grant
that permission through LuckPerms (or another Velocity permission provider),
for example `lp user <player> permission set lunachat.admin true`, then run
the command in-game. Do not depend on systemd standard input for administration.

Paper network edges ignore their local `globalChannel` and `forceJoinChannels`.
They wait for the authenticated catalog STATE, then apply Velocity defaults to
already-online and subsequently connected players. Before that point they do
not create a local channel or issue an authority-unavailable error merely
because the carrier/player handshake has not completed.

Diagnostics are distinct: no Velocity transport is `AUTHORITY_UNAVAILABLE`,
an invalid authenticated frame is `AUTHORITY_FRAME_REJECTED`, a READY session
without STATE acknowledgement is `AWAITING_CHANNEL_CATALOG`, and a configured
default/force name missing from the canonical catalog is rejected by Velocity
as `unknown canonical channel` (not an unavailable authority).

Velocity persists UUID membership and access policy in `membership-state.bin`
beside its canonical `channels.properties`. Paper uses full read-only snapshots,
never unions local YAML members. Join/leave requests use per-member
compare-and-set versions.
Leave/kick tombstones survive restarts and reject old join requests. Persistence
finishes before success is acknowledged; failures retain the previous state.
Repeated snapshots do not duplicate members. Offline UUIDs remain members and
the Bukkit recipient loop resolves only locally online players.

## Velocity policy management

For network mode, Velocity is the only policy editor. Create
`membership-policy.properties` beside `channels.properties` and restart the
Velocity plugin/proxy to apply it. It has `schema=1` and must contain every
field for every canonical channel; an incomplete file aborts startup rather
than silently opening a private channel.

```properties
schema=1
channel.<canonical-uuid>.password=
channel.<canonical-uuid>.visible=true
channel.<canonical-uuid>.world=false
channel.<canonical-uuid>.moderators=
channel.<canonical-uuid>.banned=
channel.<canonical-uuid>.muted=
channel.<canonical-uuid>.ban_expires=
channel.<canonical-uuid>.mute_expires=
```

Player lists are comma-separated UUIDs. Expiry entries are `uuid@epochMillis`
and must name an entry in the corresponding banned/muted list. This file is
read only by Velocity, persisted into the signed network state, and replicated
to Paper. Passwords are already plain-text LunaChat channel configuration;
restrict this Velocity data directory accordingly. Paper rejects all policy
edits in network mode, including option/moderator/ban/pardon/mute/unmute.

## One-time migration

Stop the network for a coordinated upgrade and back up both platforms' plugin
data. Choose ONE authoritative legacy Paper data source; do not merge backends.
Use the offline exporter with Java 21+ and SnakeYAML 2.2 on the classpath:

```sh
java -cp '/path/LunaChat.jar:/path/snakeyaml-2.2.jar' \
  com.github.ucchyocean.lc3.integration.MembershipImportTool \
  /backup/selected-paper/channels \
  /backup/velocity/channels.properties \
  /staging/membership-import.properties
```

The exporter verifies every selected channel's canonical UUID and exports its
members, password/visible/world setting, moderators, bans/mutes and expiries.
It refuses overwriting its output and never modifies the source. It imports one
selected Paper only; it never unions another backend. Review the output before
installation. Place it
beside Velocity's `channels.properties` BEFORE the first wire-5 startup.
Update all Paper and Velocity JARs together; wire 5 is incompatible with earlier wires.

On the first start only, Velocity consumes the seed and creates the durable
state. A present state file always takes precedence, even if corrupt (startup
fails rather than reimporting). The original seed is retained for audit.
An absent seed creates empty membership with joins disabled. Do not delete
`membership-state.bin` to force a reimport: doing so can resurrect removed members.
Restore catalog and membership from the same coordinated backup for rollback.

## Limits and pending release work

- Full snapshots must fit 60,000 bytes, including retained tombstones. Capacity
  failure rejects the mutation; there is no silent eviction or tombstone pruning.
- The seed enables fresh joins only for plainly open channels. Private/world/
  banned channels retain imported membership and policy but do not gain fresh
  voluntary joins.
- Join, accept, leave, force-invite and kick defer their success/default update
  until the authority reply. A rejected or timed-out change leaves the local
  replica and default channel untouched.
- Paper rejects option, moderator, ban/pardon and mute/unmute changes for a
  replicated channel. Use the Velocity-only policy file above, then restart
  the authority; accepting a local Paper edit would create a security split.
- Public integration API stays 1.0.0-SNAPSHOT; no SVSync, SuperVanish or LunaBridge
  dependency is added. Paper `Player#canSee()` logic remains unchanged.
- Automated delivery tests run the real public external API, BukkitChannel and
  modern/legacy event adapters against simulated local Bukkit players. They
  verify send calls, not Minecraft client-rendered UI or live Discord delivery.

Release gate: execute the real two-backend movement/restart/Discord integration test.
