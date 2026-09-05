# 4.0.17 debugging

Fixed: full authority STATE no longer attempts to apply replica membership to
transient personal channels (which previously threw `not an authority replica`).
Personal messages are excluded from integration publication. Players joining an
already synchronized Paper now receive the authority login/default processing.
New channels added at runtime are joinable; existing/imported restrictions are
preserved. Same-session channel creation retries retain their original result in
a bounded receipt cache. Invalid event-renamed channel names are rejected before
entering the Paper retry queue.

Validation will be recorded after the 4.0.17 package build. Real
Velocity/Paper clients and Discord were not run. Wire 6 is intentionally
incompatible with earlier wire versions.

Remaining limitations found during inspection:

- Existing channels with JOIN_DISABLED are deliberately not reopened by this
  patch. This includes channels created by earlier versions. Their intended
  access policy must be resolved explicitly rather than treating every existing
  channel as public.
- Channel deletion now treats the Velocity catalog as canonical and prunes the
  removed channel's membership/policy state during reconciliation.
- Paper create-on-join is delegated to the canonical authority. Descriptions
  remain standalone-only because they are not part of the public channel wire
  descriptor.
- Creation receipts survive retries in the running authority, not proxy restart.
