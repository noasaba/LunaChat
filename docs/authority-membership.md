# Authority membership (4.0.8-SNAPSHOT / wire 3)

Status: implementation and automated-test candidate, not yet approved for deployment.

Velocity persists UUID membership in `membership-state.bin` beside its canonical
`channels.properties`. Paper uses full read-only snapshots, never unions local
YAML members. Join/leave requests use per-member compare-and-set versions.
Leave/kick tombstones survive restarts and reject old join requests. Persistence
finishes before success is acknowledged; failures retain the previous state.
Repeated snapshots do not duplicate members. Offline UUIDs remain members and
the Bukkit recipient loop resolves only locally online players.

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

The exporter verifies every selected channel's canonical UUID, refuses restricted
(password/world/private/banned) channels, and refuses overwriting its output.
It does not modify the source. Review the output before installation. Place it
beside Velocity's `channels.properties` BEFORE the first wire-3 startup.
Update all Paper and Velocity JARs together; wire 2 is incompatible.

On the first start only, Velocity consumes the seed and creates the durable
state. A present state file always takes precedence, even if corrupt (startup
fails rather than reimporting). The original seed is retained for audit.
An absent seed creates empty membership with joins disabled. Do not delete
`membership-state.bin` to force a reimport: doing so can resurrect removed members.
Restore catalog and membership from the same coordinated backup for rollback.

## Limits and pending release work

- Full snapshots must fit 60,000 bytes, including retained tombstones. Capacity
  failure rejects the mutation; there is no silent eviction or tombstone pruning.
- The seed enables fresh joins only for reviewed open channels. Restricted
  channel access rules, moderator roles, bans and mutes are NOT migrated here.
- Paper commands still have synchronous success/default-channel side effects.
  These must be made authority-acknowledgement-aware before production release.
- Cross-backend ban/mute/access-policy convergence remains outside this
  membership-only schema. Do not deploy as a replacement for those protections.
- Public integration API stays 1.0.0-SNAPSHOT; no SVSync, SuperVanish or LunaBridge
  dependency is added. Paper `Player#canSee()` logic remains unchanged.
- Automated delivery tests run the real public external API, BukkitChannel and
  modern/legacy event adapters against simulated local Bukkit players. They
  verify send calls, not Minecraft client-rendered UI or live Discord delivery.

Release gate: resolve command acknowledgement and access-policy semantics,
then execute the real two-backend movement/restart/Discord integration test.
