# Deployment, failures, upgrade, and rollback

## Standalone Paper

Install `LunaChat.jar`, leave `integration.sharePass` empty, and explicitly
enable `accepts_external_messages` only on intended channel YAML entries.
Install one standalone LunaBridge consumer on that Paper.

## Velocity network

Install `LunaChat-Velocity.jar` on Velocity and set `sharePass` in
`plugins/lunachat/network.properties`. Put the same value in every Paper's
`integration.sharePass`. A non-empty passphrase automatically selects network
edge mode; Velocity assigns each Paper its registered-server identity during
the authenticated HELLO/READY exchange. No `role`, `serverId`, or Base64 key is
required. Install `LunaChat.jar` on each Paper and LunaBridge only on Velocity.

Use a unique passphrase of at least 12 characters. LunaChat derives the actual
256-bit key with PBKDF2-HMAC-SHA256. Legacy `sharedSecret`, `role`, and
`serverId` settings remain readable for rollback and migration, but are not
written into new configurations.

Do not combine standalone and network bridge modes. A bridge must check role and
capabilities and refuse to start on `NETWORK_EDGE`.

## Failure signals

- LunaBridge/Discord down: LunaChat local and network chat are independent.
- Velocity/transport down: Paper local rendering continues; edge network status
  becomes `UNAVAILABLE`; cross-backend and integration work is not reported as success.
- Paper down: other backends and the Velocity API continue; its bounded outbox expires.
- Passphrase mismatch/tamper/protocol/session mismatch: warning plus session removal.
- Replay: debug/fine discard, session retained.
- Queue/dedup full: explicit `OVER_CAPACITY` or warning; no unbounded growth.
- Observer failure: isolated and never fails chat delivery.

Monitor rejected frame counts, outbox-full warnings, API status/result codes,
and migration errors. Rotate a passphrase as a coordinated outage: stop edges,
replace Velocity and every Paper value, then restart Velocity before Papers.

## Upgrade and rollback

1. Back up every Paper plugin data directory and Velocity `plugins/lunachat`.
2. Check API, wire, config, and data schema release notes separately.
3. For wire-compatible updates, upgrade Velocity, then Papers, then the bridge.
4. For a wire break, stop network integrations and upgrade all LunaChat nodes
   before re-enabling the bridge; mixed versions intentionally reject frames.
5. Validate startup, role/capabilities, channel IDs, and a local message before
   enabling external publish.

Rollback within schema 1 by stopping the topology, restoring plugin jars and
the backups taken in step 1, then restarting Velocity first. For the first v0
to v1 Paper migration, `migration-backup-v0/channels` is the automatic source
for rollback; never copy it over live files while the server runs. A future
schema error requires the newer binary or an operator-approved full restore,
not a downgrade rewrite.
