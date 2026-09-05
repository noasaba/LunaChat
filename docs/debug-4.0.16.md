# 4.0.16 debugging

Fixed: full authority STATE no longer attempts to apply replica membership to
transient personal channels (which previously threw `not an authority replica`).
Personal messages are excluded from integration publication. Players joining an
already synchronized Paper now receive the authority login/default processing.
New channels added at runtime are joinable; existing/imported restrictions are
preserved. Same-session channel creation retries retain their original result in
a bounded receipt cache. Invalid event-renamed channel names are rejected before
entering the Paper retry queue.

Validation: `mvn -o -q -pl lunachat-paper,lunachat-velocity -am clean package`.
88 automated tests passed. Real Velocity/Paper clients and Discord were not run.
Wire remains 5; public API, permissions and configuration schemas are unchanged.

Remaining limitations found during inspection:

- Existing channels with JOIN_DISABLED are deliberately not reopened by this
  patch. This includes channels created by earlier versions. Their intended
  access policy must be resolved explicitly rather than treating every existing
  channel as public.
- Channel deletion still conflicts with membership-store removal protection.
  Catalog and membership updates span two files and are not a single transaction.
- Paper create descriptions and create-on-join are not delegated by the current
  creation protocol. The prior release should not be interpreted as complete
  synchronization of all channel management commands.
- Creation receipts survive retries in the running authority, not proxy restart.
