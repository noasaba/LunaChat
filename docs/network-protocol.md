# LCN2 network protocol

LCN2 is an internal LunaChat protocol, independent of LunaBridge.
Wire version 8 runs on `lunachat:network_v8` and has separate logical and secure
frame identities.

Each secure frame authenticates protocol, session UUID, startup epoch,
sequence, fresh frame UUID, optional logical UUID, frame type, timestamps, and
payload with AES-256-GCM. The operator's shared passphrase is converted to a
256-bit key with PBKDF2-HMAC-SHA256; legacy 32-byte shared secrets remain
readable during migration. Every encryption uses a random 96-bit nonce. Retry preserves the
logical UUID but creates a new frame UUID, sequence and nonce.

An authenticated `HELLO` starts or replaces a backend session without trusting
a Paper-supplied identity. Velocity derives the node ID from the actual
`ServerConnection` and returns it in `READY(nodeId)`. Paper adopts that assigned
identity for later messages. Paper repeats HELLO periodically as a
bounded heartbeat, so a carrier reconnect or a Velocity restart converges back
to READY. All later frames must match that session and epoch. Paper sends a
bounded channel `STATE` acknowledgement after READY. Velocity remains the sole
catalog owner. A Paper `/lunachat create` is sent as `CHANNEL_CREATE`; Velocity
persists the canonical ID, broadcasts a new `STATE`, and only then returns a
successful result to Paper.

`MESSAGE` is ACKed by logical ID. Velocity deduplicates before observer dispatch
and cross-backend fan-out. The authority selects the canonical final content
once; Paper edges render that immutable content without rerunning local event
or filtering transforms, preserve the decoded origin/author, and ACK the same
model. Each edge keeps a bounded logical receipt, so an ACK-loss retry (a new
frame identity) is ACKed again without a second render. An authenticated exact
replay is discarded without disconnecting the session. Tamper, wrong secret,
unknown protocol, node/session mismatch, malformed payload, and stale time
windows close that backend session (fail closed).

All replay windows, receipts, inbound render stages, and per-backend outboxes
are bounded. Entries expire. No offline queue is unbounded. Plugin messaging
uses an online player as carrier; a cold/empty backend retains only its bounded
Velocity outbox until a player creates a backend connection and handshake.
This transport does not promise immediate delivery to a backend with no active
server connection.

Private messages use `PRIVATE_REQUEST`, `PRIVATE_DELIVERY`, `PRIVATE_ACK`, and
`PRIVATE_RESULT`. They are bounded, UUID-addressed, deduplicated by logical
messageId, and never enter the canonical channel catalog or the AcceptedMessage
stream observed by LunaBridge. Success is returned only after the target Paper
delivery loop reports `DELIVERED`; all lookup, hidden, carrier, backend, render,
and timeout failures are presented as the same target-not-found message. Reply
state is a successful UUID pair in session memory; logout/restart clears it and
offline PM is intentionally unsupported.

`PRESENCE` is separate from chat. Network-edge Paper suppresses Bukkit join and
quit messages and renders one JOIN, MOVE, or QUIT notification. A visibility
provider may return PUBLIC, HIDDEN, or UNKNOWN. Presence is a connection-state
notification, not a Vanish state: ordinary players are PUBLIC when SVSync (or
another provider) is absent, explicit HIDDEN is the only Vanish-like state,
and UNKNOWN means the provider is still synchronizing. UNKNOWN is retried three
times; if it remains unresolved the notification is withheld from normal users
and a diagnostic is logged. Presence is not observed or retransmitted by
LunaBridge. Wire 8 adds the LOGIN reveal event; older wires are incompatible and must not be mixed. A mixed
deployment is unavailable until every component is upgraded.

Seeing a `PRESENCE` event therefore does not mean that the player became Vanish;
it only means the network observed JOIN, LOGIN, MOVE, or QUIT. A normal player being
represented as presence is expected. Only an explicit provider result of
`HIDDEN` is treated as Vanish for audience filtering.

Velocity retains the registered-server names from actual connection events even
while a player is HIDDEN. On a later PUBLIC observation, a player who joined
while hidden produces LOGIN followed, when necessary, by one MOVE from the
actual login server to the current server. A player who was already public does
not get a fictional LOGIN; only a real hidden-period displacement is revealed.
Multiple hidden moves are aggregated from the first relevant server to the
current server. This bounds chat output to two lines while always identifying
the current location; replaying every hop would turn rapid transfers into chat
spam. Disconnect removes the UUID-scoped history, and stale hidden paths are
bounded before reuse.

Visibility integrations are consumer-independent: the optional provider only
answers the current PUBLIC, HIDDEN, or UNKNOWN state for a Velocity Player.
LunaChat optionally discovers the `svsync` Velocity plugin and consumes its
generic current-state query and visibility listener directly; LunaBridge is not
involved. Listener events are serialized on the LunaChat scheduler and accepted
only when their global sequence, connection generation, connected flag, and
current RegisteredServer still match. Only an explicit HIDDEN-to-PUBLIC change
is a reappearance; UNKNOWN-to-PUBLIC never synthesizes LOGIN or MOVE. SuperVanish
does not call LunaChat and does not depend on it. Older or incompatible SVSync
APIs are isolated at the optional adapter boundary and fall back to PUBLIC.

JOIN, MOVE, LOGIN, and QUIT deliveries use a bounded per-backend outbox and are
removed by `PRESENCE_ACK`; Paper deduplicates retries by logical event ID. An
unsynchronized event source or destination retains Bukkit's local JOIN/QUIT
fallback and is excluded from delayed replay of that same event, preventing a
duplicate after its catalog handshake. Other backends retain the event for up
to 30 seconds so a short synchronization window does not silently lose it.

Velocity marks the registered plugin-message identifier handled before checking
the source, then accepts only backend `ServerConnection` sources. This prevents
the proxy from forwarding client-origin spoof messages.
