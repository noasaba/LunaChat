# Requirement traceability and residual risk

Status is based on code and tests in this change, not intent.

| Requirement | Contract | Implementation | Test/evidence | Status and residual risk |
| --- | --- | --- | --- | --- |
| Platform-independent public API | Roles, capabilities, immutable models, explicit results | `lunachat-api` | API compiles as its own artifact; TCK | Complete for v1 surface; release baseline binary diff still needs first non-snapshot release |
| Preserve legacy API/semantics | Additive package; no replacement/removal | Existing `lc`/`lc3` sources remain in Paper module | Existing 13 Paper tests | Complete for compile/runtime unit coverage; full third-party plugin matrix remains operational QA |
| Standalone authority | Observe and publish capabilities on Paper | `PaperIntegrationService`, Bukkit ServicesManager | TCK plus gateway tests | Implemented; process startup blocked by stopped Docker daemon |
| Network role isolation | Edge has no observe/publish and no fallback | `IntegrationRuntime.edge` | TCK `edgeCannotMasqueradeAsAuthority` | Complete |
| Final accepted boundary | Authority chooses final string once; edges render it without a second transform | `BukkitChannel` hook, canonical edge render, `PaperIntegrationService` | wire round-trip/gateway tests | Implemented; process assertion needs a running Paper client/harness |
| External idempotency | Same provider identity returns same logical UUID once | `BoundedMessageGateway` | TCK and observer duplicate test | Complete within bounded lifetime + 5-minute grace; receipts are memory-only across authority restart |
| Stable channel ID/migration | UUID primary key, backup, idempotent, future reject | `Channel`, `ChannelDataMigrator` | `ChannelDataMigratorTest` | Complete for Paper files; operator must retain backups for rollback |
| Bounded channel query | max 100, opaque cursor, safe descriptor | `BoundedChannelQueryService` | `ChannelQueryTest` | Complete |
| Authenticated transport | LCN1, AES-GCM, source/session/time checks | `SecureFrameCodec`, Paper edge, Velocity authority | tamper/wrong-secret/protocol/replay tests | Implemented; shared-secret distribution remains an operator responsibility |
| Retry/dedup/replay | logical/frame split, fresh encryption, ACK, bounded windows | `ReliableOutbox`, replay and per-authority/per-edge logical receipt maps | retry identity, replay, capacity recovery tests | Implemented in-process; ACK-loss multi-process gate awaits Docker |
| Spoof prevention | Always handled before source check; backend source validation | `VelocityNetworkAuthority.handle` | code inspection + compile against Velocity 4.1 | Implemented; proxy integration process test pending |
| Origin and UUID identity | Origin never rewritten; player author has UUID | API models/codecs/Paper adapter | codec and model validation tests | Complete for channel-message path |
| Velocity durable channel authority | Atomic schema-checked catalog | `AuthorityChannelStore` | compilation; schema behavior documented | Implemented; dedicated filesystem fault-injection test remains |
| Failure isolation | local Paper never waits on transport; observer isolated | edge outbox and bounded executors | observer failure/slow/unsubscribe tests | Complete for tested paths |
| Network membership, invite, mute/ban | Velocity authoritative global mutations | Legacy state remains Paper-local | none | Not complete; must be migrated before claiming globally consistent commands |
| Cross-backend `/tell` and `/r` | UUID reply targets in Velocity authority | Legacy local/Bungee implementation only | none | Not complete; explicitly unsupported in LCN1 v1 |
| Process integration | Paper standalone and Velocity + 2 Paper | `integration-tests` procedure | Paper 26.2 build 117 resolved; Docker socket absent | Blocked by local Docker/OrbStack daemon; not claimed passed |
| Version/schema separation | Product/API/wire/config/data versions independent | parent POM, metadata, codecs, stores | packaged metadata inspected | Complete; API binary baseline starts with 1.0.0 release |
| License/provenance | LGPL retained; no unverified GPL copy | `LICENSE`, `NOTICE` | source provenance audit | Complete for this change; future transfers require per-file record |

The two incomplete global-command rows and the blocked multi-process rows are
release blockers if the release is advertised as providing all network command
semantics. Channel-message integration and the additive public API can be
reviewed independently, but must not be described as the entire requested
network migration until those gates pass.
