# LCN2 network protocol

LCN2 is an internal LunaChat protocol, independent of LunaBridge.
Wire version 2 runs on `lunachat:network_v2` and has separate logical and secure
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
bounded channel `STATE` proposal after READY. Velocity durably applies valid
proposals before exposing them through its channel API.

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

Velocity marks the registered plugin-message identifier handled before checking
the source, then accepts only backend `ServerConnection` sources. This prevents
the proxy from forwarding client-origin spoof messages.
